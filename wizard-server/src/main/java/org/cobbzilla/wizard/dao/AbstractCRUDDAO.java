package org.cobbzilla.wizard.dao;

/**
 * Forked from dropwizard https://github.com/dropwizard/
 * https://github.com/dropwizard/dropwizard/blob/master/LICENSE
 */

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.Transformer;
import org.cobbzilla.util.collection.FieldTransfomer;
import org.cobbzilla.wizard.model.Identifiable;
import org.hibernate.FlushMode;
import org.hibernate.criterion.Restrictions;
import org.springframework.transaction.annotation.Transactional;

import javax.validation.Valid;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkNotNull;

@Transactional @Slf4j
public abstract class AbstractCRUDDAO<E extends Identifiable> extends AbstractDAO<E> {

    public static final Transformer TO_UUID = new FieldTransfomer("uuid");
    public static <E> Collection<String> toUuid (Collection<E> c) { return CollectionUtils.collect(c, TO_UUID); }

    @Transactional(readOnly=true)
    @Override public List<E> findAll() { return list(criteria()); }

    @Transactional(readOnly=true)
    @Override public E findByUuid(String uuid) { return uniqueResult(criteria().add(Restrictions.eq("uuid", uuid))); }

    @Transactional(readOnly=true)
    public List<E> findByUuids(Collection<String> uuids) {
        return list(criteria().add(Restrictions.in("uuid", uuids)));
    }

    @Transactional(readOnly=true)
    @Override public boolean exists(String uuid) { return findByUuid(uuid) != null; }

    @Override public Object preCreate(@Valid E entity) { return entity; }
    @Override public E postCreate(E entity, Object context) { return entity; }

    @Override public E create(@Valid E entity) {
        entity.beforeCreate();
        final Object ctx = preCreate(entity);
        setFlushMode();
        entity.setUuid((String) getHibernateTemplate().save(checkNotNull(entity)));
        getHibernateTemplate().flush();
        return postCreate(entity, ctx);
    }

    @Override public E createOrUpdate(@Valid E entity) {
        return (entity.getUuid() == null) ? create(entity) : update(entity);
    }

    public E upsert(@Valid E entity) {
        if (entity.getUuid() == null) throw new IllegalArgumentException("upsert: uuid must not be null");
        return exists(entity.getUuid()) ? update(entity) : create(entity);
    }

    @Override public Object preUpdate(@Valid E entity) { return entity; }
    @Override public E postUpdate(@Valid E entity, Object context) { return entity; }

    @Override public E update(@Valid E entity) {
        final Object ctx = preUpdate(entity);
        setFlushMode();
        entity = getHibernateTemplate().merge(checkNotNull(entity));
        getHibernateTemplate().flush();
        return postUpdate(entity, ctx);
    }

    @Override public void delete(String uuid) {
        final E found = get(checkNotNull(uuid));
        setFlushMode();
        if (found != null) getHibernateTemplate().delete(found);
        getHibernateTemplate().flush();
    }

    @Transactional(readOnly=true)
    @Override public E findByUniqueField(String field, Object value) {
        return uniqueResult(Restrictions.eq(field, value));
    }

    @Transactional(readOnly=true)
    @Override public List<E> findByField(String field, Object value) {
        return list(criteria().add(Restrictions.eq(field, value)));
    }

    @Transactional(readOnly=true)
    public E cacheLookup(String uuid, Map<String, E> cache) {
        final E thing = cache.get(uuid);
        return (thing != null) ? thing : findByUuid(uuid);
    }

    protected void setFlushMode() { getHibernateTemplate().getSessionFactory().getCurrentSession().setFlushMode(FlushMode.COMMIT); }

}
