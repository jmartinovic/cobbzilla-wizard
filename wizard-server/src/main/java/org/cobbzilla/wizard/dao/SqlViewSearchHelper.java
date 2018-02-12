package org.cobbzilla.wizard.dao;

import lombok.extern.slf4j.Slf4j;
import org.cobbzilla.util.jdbc.ResultSetBean;
import org.cobbzilla.util.reflect.ReflectionUtil;
import org.cobbzilla.wizard.model.*;
import org.cobbzilla.wizard.server.config.RestServerConfiguration;
import org.jasypt.hibernate4.encryptor.HibernatePBEStringEncryptor;

import java.util.*;
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

        final List<Object> params = new ArrayList<>();
        final boolean searchByEncryptedField = Arrays.stream(fields).anyMatch(a -> a.isUsedForFiltering() && a.isEncrypted());

        if (resultPage.getHasFilter() && !searchByEncryptedField) {
            final String filter = dao.buildFilter(resultPage, params);
            if (!empty(filter)) sql.append(" AND (").append(filter).append(") ");
        }

        if (resultPage.getHasBounds()) {
            for (String bound : resultPage.getBounds().keySet()) {
                sql.append(" AND (").append(dao.buildBound(bound, resultPage.getBounds().get(bound), params))
                                    .append(") ");
            }
        }

        final String sort;
        final String sortedField;
        if (resultPage.getHasSortField()) {
            sortedField = dao.getSortField(resultPage.getSortField());
            sort = sortedField + " " + resultPage.getSortOrder();
        } else {
            sort = dao.getDefaultSort();
            sortedField = sort.split(" ")[0];
        }

        final String offset;
        final String limit;
        final String sortClause;
        if (searchByEncryptedField) {
            offset =  "";
            limit = "";
            sortClause = "";
        } else {
            offset = " OFFSET " + resultPage.getPageOffset();
            limit = " LIMIT " + resultPage.getPageSize();
            sortClause = " ORDER BY "  + sort;
        }

        final String query = "select * " + sql.toString() + sortClause + limit + offset;

        Integer totalCount = null;
        final ArrayList<E> thingsList = new ArrayList<>();

        try {
            final Object[] args = params.toArray();

            final ResultSetBean rs = configuration.execSql(query, args);
            final List<Future<?>> results = new ArrayList<>(rs.rowCount());
            final ExecutorService exec = searchByEncryptedField ? fixedPool(Math.min(16, rs.rowCount())) : null;

            for (Map<String, Object> row : rs.getRows()) {
                if (searchByEncryptedField) {
                    // we'll sort them later and there might be many rows, populate in parallel
                    results.add(exec.submit(() -> {
                        final E thing = (E) populate(instantiate(resultClass), row, fields, hibernateEncryptor);
                        synchronized (thingsList) {
                            thingsList.add(thing);
                        }
                    }));
                } else {
                    // no encrypted fields, SQL has an offset + limit + sort, just populate all rows
                    final E thing = (E) populate(instantiate(resultClass), row, fields, hibernateEncryptor);
                    thingsList.add(thing);
                }
            }

            if (!searchByEncryptedField) {
                totalCount = configuration.execSql("select count(*) "+sql.toString(), args).countOrZero();
                return new SearchResults<>(thingsList, totalCount);
            }

            // wait for encrypted rows to populate
            awaitAll(results, SEARCH_TIMEOUT);

            // find matches among all candidates
            final List<Future<?>> resultsEncrypted = new ArrayList<>();
            final List<E> matched = new ArrayList<>();
            for (E thing : thingsList) {
                if (thing instanceof FilterableSqlViewSearchResult) {
                    if (resultPage.getHasFilter()) {
                        resultsEncrypted.add(exec.submit(() -> {
                            if (((FilterableSqlViewSearchResult) thing).matches(resultPage.getFilter())) {
                                synchronized (matched) { matched.add(thing); }
                            }
                        }));
                    } else {
                        matched.add(thing);
                    }
                }
            }
            awaitAll(resultsEncrypted, SEARCH_TIMEOUT);

            // manually sort and apply offset + limit
            final SqlViewField sqlViewField = Arrays.stream(fields).filter(a -> a.getName().equals(sortedField)).findFirst().get();
            final Comparator<E> comparator = (E o1, E o2) -> compareSelectedItems(o1, o2, sortedField, sqlViewField);

            if (!resultPage.getSortOrder().equals(DEFAULT_SORT)) {
                matched.sort(comparator);
            } else {
                matched.sort(comparator.reversed());
            }

            totalCount = matched.size();
            int startIndex = resultPage.getPageOffset();
            if (matched.size() < startIndex) {
                return new SearchResults<>(new ArrayList<>(), totalCount);
            } else {
                int endIndex = startIndex + resultPage.getPageSize() + resultPage.getPageOffset();
                endIndex = min(endIndex, matched.size()); // ensure we do not run past the end of our matches
                return new SearchResults<>(matched.subList(startIndex, endIndex), totalCount);
            }

        } catch (Exception e) {
            return die("search: "+e, e);
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

        throw invalidEx("err.sort.invalid", "Sort field has invalid type");
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
