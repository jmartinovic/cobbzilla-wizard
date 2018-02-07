package org.cobbzilla.wizard.dao;

import com.google.code.yanf4j.util.ConcurrentHashSet;
import lombok.extern.slf4j.Slf4j;
import org.cobbzilla.util.jdbc.ResultSetBean;
import org.cobbzilla.util.reflect.ReflectionUtil;
import org.cobbzilla.wizard.model.Identifiable;
import org.cobbzilla.wizard.model.ResultPage;
import org.cobbzilla.wizard.model.SqlViewField;
import org.cobbzilla.wizard.model.SqlViewSearchResult;
import org.cobbzilla.wizard.server.config.RestServerConfiguration;
import org.jasypt.hibernate4.encryptor.HibernatePBEStringEncryptor;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static java.lang.Integer.min;
import static org.cobbzilla.util.daemon.Await.awaitAll;
import static org.cobbzilla.util.daemon.DaemonThreadFactory.fixedPool;
import static org.cobbzilla.util.daemon.ZillaRuntime.die;
import static org.cobbzilla.util.daemon.ZillaRuntime.empty;
import static org.cobbzilla.util.reflect.ReflectionUtil.instantiate;
import static org.cobbzilla.wizard.model.ResultPage.DEFAULT_SORT;
import static org.cobbzilla.wizard.resources.ResourceUtil.invalidEx;

@Slf4j
public class SqlViewSearchHelper {

    public static final long SEARCH_TIMEOUT =  TimeUnit.SECONDS.toMillis(20);

    public static <E extends Identifiable, R extends SqlViewSearchResult>
    SearchResults<E> search(SqlViewSearchableDAO<E> dao,
                            ResultPage resultPage,
                            Class<R> resultClass,
                            SqlViewField[] fields,
                            HibernatePBEStringEncryptor hibernateEncryptor,
                            RestServerConfiguration configuration) {

        final StringBuilder sql = new StringBuilder("from " + dao.getSearchView()
                                                    + " where (").append(dao.fixedFilters()).append(") ");
        final StringBuilder sqlWithoutFilters = new StringBuilder(sql);

        final List<Object> params = new ArrayList<>();
        final List<Object> paramsForEncrypted = new ArrayList<>();

        if (resultPage.getHasFilter()) {
            sql.append(" AND (").append(dao.buildFilter(resultPage, params)).append(") ");
        }

        if (resultPage.getHasBounds()) {
            for (String bound : resultPage.getBounds().keySet()) {
                sql.append(" AND (").append(dao.buildBound(bound, resultPage.getBounds().get(bound), params))
                                    .append(") ");
                sqlWithoutFilters.append(" AND (")
                                 .append(dao.buildBound(bound, resultPage.getBounds().get(bound), paramsForEncrypted))
                                 .append(") ");
            }
        }

        boolean searchByEncryptedField = Arrays.stream(fields).anyMatch(a -> a.isUsedForFiltering() && a.isEncrypted());
        final String sort;
        final String sortedField;
        if (resultPage.getHasSortField()) {
            sortedField = dao.getSortField(resultPage.getSortField());
            sort = sortedField + " " + resultPage.getSortOrder();
        } else {
            sort = dao.getDefaultSort();
            sortedField = sort.split(" ")[0];
        }

        String offset = " OFFSET " + resultPage.getPageOffset();
        int limit = resultPage.getPageSize();
        if (searchByEncryptedField) {
            offset =  "";
            limit += resultPage.getPageOffset();
        }

        final String uuidsSql = "select uuid " + sql.toString();
        final String query = "select * " + sql.toString()
                + " ORDER BY " + sort
                + " LIMIT " + limit
                + offset;

        Integer totalCount = null;
        ArrayList<E> thingsList;
        final Map<String,E> things = new ConcurrentHashMap<>();
        final Set<String> allUuids = new ConcurrentHashSet<>();

        try {
            final Object[] args = params.toArray();
            ResultSetBean uuidsResult = configuration.execSql(uuidsSql, args);
            allUuids.addAll(uuidsResult.getColumnValues("uuid"));

            if (allUuids.size() > 0) {
                final ResultSetBean rs = configuration.execSql(query, args);
                final List<Future<?>> results = new ArrayList<>(rs.rowCount());
                final ExecutorService exec = fixedPool(Math.min(16, rs.rowCount()));

                for (Map<String, Object> row : rs.getRows()) {
                    results.add(exec.submit(() -> {
                        E thing = (E) populate(instantiate(resultClass), row, fields, hibernateEncryptor);
                        things.put(thing.getUuid(), thing);
                    }));
                }
                awaitAll(results, SEARCH_TIMEOUT);
            }

            if (searchByEncryptedField) {
                paramsForEncrypted.add(allUuids.toArray());
                final Object[] argsForEncrypted = paramsForEncrypted.toArray();
                final String queryForEncrypted = "select * " + sqlWithoutFilters.toString()
                        + "AND uuid NOT IN (SELECT * FROM unnest( ? )) "
                        + " ORDER BY " + sort;

                final ResultSetBean rsEncrypted = configuration.execSql(queryForEncrypted, argsForEncrypted);

                if (!rsEncrypted.isEmpty()) {
                    int threadCount = Math.min(rsEncrypted.rowCount(), 16);
                    final List<Future<?>> resultsEncrypted = new ArrayList<>(rsEncrypted.rowCount());
                    final ExecutorService execEncrypted = fixedPool(threadCount);

                    for (Map<String, Object> row : rsEncrypted.getRows()) {
                        resultsEncrypted.add(execEncrypted.submit(() -> {
                            E thing = (E) populateAndFilter(instantiate(resultClass), row, fields, hibernateEncryptor,
                                                            resultPage.getFilter());
                            if (!empty(thing)) {
                                if (!allUuids.contains(thing.getUuid())) {
                                    things.put(thing.getUuid(), thing);
                                    allUuids.add(thing.getUuid());
                                }
                            }
                        }));
                    }
                    awaitAll(resultsEncrypted, SEARCH_TIMEOUT);
                }
            }

        } catch (Exception e) {
            log.warn("error determining total count: "+e);
        }

        thingsList = new ArrayList<>(things.values());
        SqlViewField sqlViewField = Arrays.stream(fields).filter(a -> a.getName().equals(sortedField)).findFirst().get();

        if (searchByEncryptedField) {
            final Comparator<E> comparator = (E o1, E o2) -> {
                return compareSelectedItems(o1, o2, sortedField, sqlViewField);
            };

            if (!resultPage.getSortOrder().equals(DEFAULT_SORT)) {
                thingsList.sort(comparator);
            } else {
                thingsList.sort(comparator.reversed());
            }
        }

        totalCount = allUuids.size();
        int startIndex = resultPage.getPageOffset();
        if (thingsList.size() < startIndex) {
            return new SearchResults<>(new ArrayList<>(), totalCount);
        } else {

            int endIndex = min(resultPage.getPageSize() + resultPage.getPageOffset(), things.size());
            return new SearchResults<>(thingsList.subList(startIndex, endIndex), totalCount);
        }
    }

    private static <E extends Identifiable> int compareSelectedItems(E o1, E o2,
                                                                     String sortedField,
                                                                     SqlViewField field) {
        Object fieldObject1;
        Object fieldObject2;

        if (field.hasEntity()) {
            fieldObject1 = ReflectionUtil.get(ReflectionUtil.get(ReflectionUtil.get(o1, "related"),
                                                                 field.getEntity()), field.getEntityProperty());
            fieldObject2 = ReflectionUtil.get(ReflectionUtil.get(ReflectionUtil.get(o2, "related"),
                                                                 field.getEntity()), field.getEntityProperty());
        } else {
            fieldObject1 = ReflectionUtil.get(o1, sortedField);
            fieldObject2 = ReflectionUtil.get(o2, sortedField);
        }

        if (fieldObject1 == null && fieldObject2 == null) return 0;
        if (fieldObject1 == null && fieldObject2 != null) return 1;
        if (fieldObject1 != null && fieldObject2 == null) return -1;

        Class sortedFieldClass = ReflectionUtil.getSimpleClass(fieldObject1);
        if (sortedFieldClass.equals(String.class)) {
            return ((String) fieldObject1).compareTo((String) fieldObject2);
        } else if (sortedFieldClass.equals(Long.class)) {
            return ((Long) fieldObject1).compareTo((Long) fieldObject2);
        } else if (sortedFieldClass.equals(Integer.class)) {
            return ((Integer) fieldObject1).compareTo((Integer) fieldObject2);
        } else if (sortedFieldClass.equals(Boolean.class)) {
            return ((Boolean) fieldObject1).compareTo((Boolean) fieldObject2);
        }

        throw invalidEx("Sort field has invalid type");
    }

    public static <T extends SqlViewSearchResult, E extends Identifiable> T populateAndFilter(T thing,
                                                                                              Map<String, Object> row,
                                                                                              SqlViewField[] fields,
                                                                                              HibernatePBEStringEncryptor hibernateEncryptor,
                                                                                              String filter) {
        boolean containsFilterValue = false;
        for (SqlViewField field : fields) {
            final Class<? extends Identifiable> type = field.getType();
            Object target = thing;
            if (type != null) {
                // sanity check, should never happen
                if (!field.hasEntity()) die("populate: type was "+type.getName()+" but entity was null: "+field);
                target = thing.getRelated().entity(type, field.getEntity());
            }
            final Object value = getValue(row, field.getName(), hibernateEncryptor, field.isEncrypted());
            if (!containsFilterValue && !empty(value)
                && field.isUsedForFiltering()
                && value.toString().toLowerCase().contains(filter.toLowerCase())) {
                containsFilterValue = true;
            }
            if (field.hasSetter()) {
                field.getSetter().set(target, field.getEntityProperty(), value);
            } else {
                ReflectionUtil.set(target, field.getEntityProperty(), value);
            }
        }
        return containsFilterValue ? thing : null;
    }

    public static <T extends SqlViewSearchResult> T populate(T thing,
                                                             Map<String, Object> row,
                                                             SqlViewField[] fields,
                                                             HibernatePBEStringEncryptor hibernateEncryptor) {
        for (SqlViewField field : fields) {
            final Class<? extends Identifiable> type = field.getType();
            Object target = thing;
            if (type != null) {
                if (!field.hasEntity()) die("populate: type was "+type.getName()+" but entity was null: "+field); // sanity check, should never happen
                target = thing.getRelated().entity(type, field.getEntity());
            }
            final Object value = getValue(row, field.getName(), hibernateEncryptor, field.isEncrypted());
            if (field.hasSetter()) {
                field.getSetter().set(target, field.getEntityProperty(), value);
            } else {
                ReflectionUtil.set(target, field.getEntityProperty(), value);
            }
        }
        return thing;
    }

    public static Object getValue(Map<String, Object> row,
                                  String field,
                                  HibernatePBEStringEncryptor hibernateEncryptor,
                                  boolean encrypted) {
        final Object value = row.get(field);
        return value == null || !encrypted ? value : hibernateEncryptor.decrypt(value.toString());
    }
}
