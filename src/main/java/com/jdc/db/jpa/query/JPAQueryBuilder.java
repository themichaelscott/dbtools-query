/*
 * QueryBuilder.java
 *
 * Created on November 4, 2007
 *
 * Copyright 2007 Jeff Campbell. All rights reserved. Unauthorized reproduction 
 * is a violation of applicable law. This material contains certain 
 * confidential or proprietary information and trade secrets of Jeff Campbell.
 */
package com.jdc.db.jpa.query;

import com.jdc.db.shared.query.QueryCompareType;
import com.jdc.db.shared.query.QueryUtil;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import javax.persistence.EntityManager;
import javax.persistence.Query;

/**
 *
 * @author  jeff
 */
public class JPAQueryBuilder<T extends Object> implements Cloneable {

    public static final int NO_OR_GROUP = -1;
    // NOTE: if any NEW variables are added BE SURE TO PUT IT INTO THE clone() method

    private EntityManager entityManager = null;
    private List<Field> fields;
    private List<String> objects;
    private List<String> varNames;
    private List<FilterItem> joins;
    private List<FilterItem> stdFilters; // just filters ANDed together

    private Map<Integer, List<FilterItem>> filtersMap;
    private List<String> andClauses; //extra and clauses

    private List<String> groupBys;
    private List<String> orderBys;
    private String selectClause;
    private String postSelectClause;

    /** Creates a new instance of QueryMaker */
    public JPAQueryBuilder() {
        reset();
    }

    public JPAQueryBuilder(EntityManager entityManager) {
        this.setEntityManager(entityManager);
        reset();
    }

    public void close() {
        if (entityManager != null && entityManager.isOpen()) {
            entityManager.close();
        }

        entityManager = null; // NOPMD - Null is expected (forcing a new entity manager to be specified)
    }

    @Override
    public Object clone() {
        Class thisClass = this.getClass();
        
        JPAQueryBuilder clone;
        try {
            clone = (JPAQueryBuilder) thisClass.newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("Could not clone QueryBuilder", e);
        }
        
        if (entityManager != null) {
            clone.setEntityManager(entityManager);
        }

        // mutable.... create new objects!
        clone.fields = new ArrayList<Field>(fields);
        clone.objects = new ArrayList<String>(objects);
        clone.varNames = new ArrayList<String>(varNames);

        clone.joins = new ArrayList<FilterItem>(joins);
        clone.stdFilters = new ArrayList<FilterItem>(stdFilters);

        // filters Map
        clone.filtersMap = new HashMap<Integer, List<FilterItem>>();
        for (Entry<Integer, List<FilterItem>> e : filtersMap.entrySet()) {
            List<FilterItem> clonedFilters = new ArrayList<FilterItem>(e.getValue());
            clone.filtersMap.put(e.getKey(), clonedFilters);
        }

        clone.andClauses = new ArrayList<String>(andClauses); //extra and clauses
        clone.groupBys = new ArrayList<String>(groupBys);
        clone.orderBys = new ArrayList<String>(orderBys);

        // immutable.... just assign
        clone.selectClause = selectClause;
        clone.postSelectClause = postSelectClause;

        clone.internalVarUsed = internalVarUsed;
        clone.objectMap = new HashMap<String, String>(objectMap);
        
        return clone;
    }

    public void reset() {
        fields = new ArrayList<Field>();
        objects = new ArrayList<String>();
        varNames = new ArrayList<String>();
        joins = new ArrayList<FilterItem>();
        stdFilters = new ArrayList<FilterItem>();
        filtersMap = new HashMap<Integer, List<FilterItem>>();
        andClauses = new ArrayList<String>();
        groupBys = new ArrayList<String>();
        orderBys = new ArrayList<String>();

        selectClause = "";
        postSelectClause = "";
    }

    public Query executeQuery() {
        return executeQuery(false);
    }
    
    public int executeCountQuery() {
        int count = 0;
        
        Query query = executeQuery(true);
        Object o = query.getSingleResult();
        if (o != null) {
            count = ((Long) o).intValue();
        }
        
        return count;
    }
    
    private Query executeQuery(boolean countOnly) {
        if (entityManager != null) {
            Query query = entityManager.createQuery(this.toString(countOnly));
            
            // add on any parameters
            for (FilterItem stdFilter : stdFilters) {
                if (stdFilter.isParameterFilter()) {
                    query.setParameter(stdFilter.getParamName(), stdFilter.getParamValue());
                }
            }
            
            return query;
        } else {
            System.out.println("WARNING... executeQuery called with a null entityManager.");
            return null;
        }
    }
    
    public Object getSingleResult() {
        Query q = executeQuery();
        return q.getSingleResult();
    }
    
    public List getResultList() {
        Query q = executeQuery();
        return q.getResultList();
    }

    /**
     * Adds a column to the query
     * @return columnID (or the order in which it was added... 0 based)
     */
    public int addField(String varName) {
        if (!internalVarUsed) {
            throw new IllegalStateException("Cannot call addField(varName) when internal var is not being used");
        }
            
        addField(DEFAULT_VAR, varName);
        return fields.size() - 1;
    }

    /**
     * Adds a column to the query
     * @return columnID (or the order in which it was added... 0 based)
     */
    public int addField(String object, String varName) {
        checkObjectForField(object);
        fields.add(new Field(object + "." + varName));
        return fields.size() - 1;
    }
    
    private void checkObjectForField(String objectName) {
        if (!objectMap.containsKey(objectName)) {
            throw new IllegalArgumentException("object named ["+ objectName +"] does not exist.  Be sure to call addObject(objectClassName) before adding fields for this object.");
        }
    }

    public static final String DEFAULT_VAR = "dfltObj";
    private boolean internalVarUsed = false;
    private Map<String, String> objectMap = new HashMap<String, String>();
    public void addObject(String objectClassName) {
        if (objectMap.size() > 0) {
            throw new IllegalStateException("Cannot call addObject(objectClassName) multiple times.  Use addObject(objectClassName, varNameForObject)");
        }
        
        addObject(objectClassName, DEFAULT_VAR);
    }
    
    public void addObject(String objectClassName, String varNameForObject) {
        if (varNameForObject.equals(DEFAULT_VAR)) {
            internalVarUsed = true;
        }
        
        varNames.add(varNameForObject);
        objects.add(objectClassName +" "+ varNameForObject);
        
        objectMap.put(varNameForObject, objectClassName);
    }

    public void addJoin(String field, String field2) {
        joins.add(new FilterItem(field, QueryCompareType.EQUAL, field2));
    }

    private List<FilterItem> getFilters(int orGroupKey) {
        // get the filters for the given OR key
        List<FilterItem> filters;
        if (orGroupKey == NO_OR_GROUP) {
            filters = stdFilters;
        } else {
            filters = filtersMap.get(orGroupKey);
            if (filters == null) {
                filters = new ArrayList<FilterItem>();
                filtersMap.put(orGroupKey, filters);
            }
        }

        return filters;
    }

    private String getOnlyVarName() {
        if (varNames.size() == 1) {
            return varNames.get(0);
        } else if (varNames.size() > 1) {
            throw new IllegalStateException("There are more than one object!");
        } else {
            throw new IllegalStateException("There are no objects!");
        }
    }
    
    public void addFilter(String varName, String value) {
        addFilter(getOnlyVarName(), varName, QueryCompareType.EQUAL, value);
    }
    
    public void addFilter(String objectVarName, String varName, String value) {
        addFilter(objectVarName, varName, QueryCompareType.EQUAL, value);
    }

    public void addFilter(String varName, QueryCompareType compare, String value) {
        addFilter(getOnlyVarName(), varName, compare, value, NO_OR_GROUP);
    }
    
    public void addFilter(String objectVarName, String varName, QueryCompareType compare, String value) {
        addFilter(objectVarName, varName, compare, value, NO_OR_GROUP);
    }

    public void addFilter(String varName, QueryCompareType compare, String value, int orGroupKey) {
        addFilter(getOnlyVarName(), varName, compare, value, orGroupKey);
    }
    
    public void addFilter(String objectVarName, String varName, QueryCompareType compare, String value, int orGroupKey) {
        // get the filters for the given OR key
        List<FilterItem> filters = getFilters(orGroupKey);

        if (compare != QueryCompareType.LIKE && compare != QueryCompareType.LIKE_IGNORECASE) {
            filters.add(new FilterItem(objectVarName +"."+ varName, compare, formatString(value)));
        } else {
            filters.add(new FilterItem(objectVarName +"."+ varName, compare, value));
        }
    }

    public void addFilter(String varName, int value) {
        addFilter(getOnlyVarName(), varName, value, NO_OR_GROUP);
    }
    
    public void addFilter(String objectVarName, String varName, int value) {
        addFilter(objectVarName, varName, value, NO_OR_GROUP);
    }

    public void addFilter(String varName, int value, int orGroupKey) {
        addFilter(getOnlyVarName(), varName, value, orGroupKey);
    }
    
    public void addFilter(String objectVarName, String varName, int value, int orGroupKey) {
        // get the filters for the given OR key
        List<FilterItem> filters = getFilters(orGroupKey);

        filters.add(new FilterItem(objectVarName +"."+ varName, QueryCompareType.EQUAL, Integer.toString(value)));
    }

    public void addFilter(String varName, QueryCompareType compare, int value) {
        addFilter(getOnlyVarName(), varName, compare, value, NO_OR_GROUP);
    }
    
    public void addFilter(String objectVarName, String varName, QueryCompareType compare, int value) {
        addFilter(objectVarName, varName, compare, value, NO_OR_GROUP);
    }

    public void addFilter(String varName, QueryCompareType compare, int value, int orGroupKey) {
        addFilter(getOnlyVarName(), varName, compare, value, orGroupKey);
    }
    
    public void addFilter(String objectVarName, String varName, QueryCompareType compare, int value, int orGroupKey) {
        // get the filters for the given OR key
        List<FilterItem> filters = getFilters(orGroupKey);

        filters.add(new FilterItem(objectVarName +"."+ varName, compare, Integer.toString(value)));
    }

    public void addFilter(String varName, Date value) {
        addFilter(getOnlyVarName(), varName, QueryCompareType.EQUAL, value);
    }
    
    public void addFilter(String objectVarName, String varName, Date value) {
        addFilter(objectVarName, varName, QueryCompareType.EQUAL, value);
    }

    public void addFilter(String varName, QueryCompareType compare, Date value) {
        addFilter(getOnlyVarName(), varName, compare, value, NO_OR_GROUP);
    }
    
    public void addFilter(String objectVarName, String varName, QueryCompareType compare, Date value) {
        addFilter(objectVarName, varName, compare, value, NO_OR_GROUP);
    }

    public void addFilter(String varName, QueryCompareType compare, Date value, int orGroupKey) {
        addFilter(getOnlyVarName(), varName, compare, value, orGroupKey);
    }
    
    public void addFilter(String objectVarName, String varName, QueryCompareType compare, Date value, int orGroupKey) {
        // get the filters for the given OR key
        List<FilterItem> filters = getFilters(orGroupKey);

        filters.add(new FilterItem(objectVarName +"."+ varName, compare, value));
    }

    public void addGroupBy(String varName) {
        groupBys.add(DEFAULT_VAR +"."+ varName);
    }
    
    public void addGroupBy(String objectVarName, String varName) {
        groupBys.add(objectVarName +"."+ varName);
    }

    public void addOrderBy(String varName) {
        orderBys.add(DEFAULT_VAR +"."+ varName);
    }
    
    public void addOrderBy(String objectVarName, String varName) {
        orderBys.add(objectVarName +"."+ varName);
    }

    public void addAndCalause(String c) {
        andClauses.add(c);
    }

    public String buildQuery() {
        return buildQuery(false);
    }
    
    public String buildQuery(boolean countOnly) {
        selectClause = "";
        postSelectClause = "";

        StringBuilder query = new StringBuilder("SELECT ");
        containsItems = false;

        // fields
        if (countOnly) {
            query.append("count(*)");
        } else {
            if (fields.size() > 0) {
                addListItems(query, fields);
            } else {
                if (objects.size() == 1) {
                    List<Field> tempFields = new ArrayList<Field>();
                    tempFields.add(new Field(varNames.get(0)));
                    addListItems(query, tempFields);
                } else {
                    throw new IllegalStateException("There must be at least 1 field if there is more than 1 object");
                }
            }
        }

        // save select portion
        selectClause = query.toString();

        // table names
        query = new StringBuilder();
        query.append(" FROM ");
        containsItems = false;
        addListItems(query, objects);

        // add filters
        if (joins.size() > 0 || stdFilters.size() > 0 || filtersMap.size() > 0 || andClauses.size() > 0) {
            query.append(" WHERE ");
            containsItems = false;
        }

        if (joins.size() > 0) {
            addListItems(query, joins, " AND ");
        }

        if (stdFilters.size() > 0) {
            addListItems(query, stdFilters, " AND ");
        }

        int orCount = 0;
        for (Entry<Integer, List<FilterItem>> e :filtersMap.entrySet()) {
            containsItems = false;
            
            if (orCount == 0) {
                query.append(" (");
            } else {
                query.append(" OR (");
            }
            orCount++;
            
            addListItems(query, e.getValue(), " AND ");
            query.append(")");
        }

        if (andClauses.size() > 0) {
            addListItems(query, andClauses, " AND ");
        }

        // add groupbys
        if (groupBys.size() > 0) {
            query.append(" GROUP BY ");
            containsItems = false;
            addListItems(query, groupBys);
        }

        // add groupbys
        if (orderBys.size() > 0) {
            query.append(" ORDER BY ");
            containsItems = false;
            addListItems(query, orderBys);
        }

        postSelectClause = query.toString();

        return selectClause + postSelectClause;
    }

    @Override
    public String toString() {
        return buildQuery();
    }

    public String toString(boolean countOnly) {
        return buildQuery(countOnly);
    }
    
    private void addListItems(StringBuilder query, List list) {
        addListItems(query, list, ", ");
    }
    private boolean containsItems = false;

    private void addListItems(StringBuilder query, List list, String seperator) {
        for (int i = 0; i < list.size(); i++) {
            if (containsItems) {
                query.append(seperator);
            }

            query.append(list.get(i));

            if (!containsItems) {
                containsItems = true;
            }
        }
    }

    private class Field {

        private String name;
        private String alias;

        public Field(String name) {
            this.name = name;
        }

        public Field(String name, String alias) {
            this.name = name;
            this.alias = alias;
        }

        @Override
        public String toString() {
            String fieldStr = name;

            if (alias != null && !alias.equals("")) {
                fieldStr += " AS " + alias;
            }

            return fieldStr;
        }
    }

    private enum FilterType{STRING, DATE};
    private static int filterParamCount = 0;
    private class FilterItem {
        private FilterType type = FilterType.STRING;
        private String field;
        private String value;
        private QueryCompareType compare;
        
        private Object paramValue;
        private String paramName;
        private boolean paramFilter = false;

        public FilterItem(String field, QueryCompareType compare, String value) {
            type = FilterType.STRING;
            this.field = field;
            this.value = value;
            this.compare = compare;
        }
        
        public FilterItem(String field, QueryCompareType compare, Date value) {
            type = FilterType.DATE;
            this.field = field;
            this.compare = compare;
            
            paramFilter = true;
            paramName = "dateParam"+ (++filterParamCount);
            this.value = ":"+ paramName;
            this.paramValue = value;
        }

        public boolean isParameterFilter() {
            return paramFilter;
        }
        
        public FilterType getType() {
            return type;
        }

        public String getParamName() {
            return paramName;
        }

        public Object getParamValue() {
            return paramValue;
        }
        
        @Override
        public String toString() {
            String filter = "";

            String filterCompare = " = ";
            switch (compare) {
                default:
                case EQUAL:
                    filterCompare = " = ";
                    break;
                case GREATERTHAN:
                    filterCompare = " > ";
                    break;
                case LESSTHAN:
                    filterCompare = " < ";
                    break;
                case GREATERTHAN_EQUAL:
                    filterCompare = " >= ";
                    break;
                case LESSTHAN_EQUAL:
                    filterCompare = " <= ";
                    break;
                case LIKE:
                case LIKE_IGNORECASE:
                    filterCompare = "";
                    break;
            }

            
            if (compare != QueryCompareType.LIKE && compare != QueryCompareType.LIKE_IGNORECASE) {
                filter = field + filterCompare + value;
            } else {
                if (type != FilterType.STRING ) {
                    throw new IllegalStateException("Cannot have a like clause on this filter type ["+ type +"] for field ["+ field +"]");
                }
                switch (compare) {
                    case LIKE:
                        filter = formatLikeClause(field, value);
                        break;
                    case LIKE_IGNORECASE:
                        filter = formatIgnoreCaseLikeClause(field, value);
                        break;
                    default:
                        filter = field + " LIKE '%" + value + "%'";
                }

            }

            return filter;
        }
    }

    public String formatLikeClause(String column, String value) {
        return QueryUtil.formatLikeClause(column, value);
    }

    public String formatIgnoreCaseLikeClause(String column, String value) {
        return formatLikeClause(column, value);
    }

    /** Getter for property selectClause.
     * @return Value of property selectClause.
     *
     */
    public java.lang.String getSelectClause() {
        if (selectClause.length() == 0) {
            buildQuery();
        }

        return selectClause;
    }

    /** Getter for property postSelectClause.
     * @return Value of property postSelectClause.
     *
     */
    public java.lang.String getPostSelectClause() {
        return postSelectClause;
    }

    public EntityManager getEntityManager() {
        return entityManager;
    }

    public void setEntityManager(EntityManager dbManager) {
        this.entityManager = dbManager;
    }

    public static final String formatString(String str) {
        return formatString(str, true);
    }

    public static String formatString(String str, boolean wrap) {
        return QueryUtil.formatString(str, wrap);
    }

    public static String formatDate(java.util.Date date) {
        if (date == null || date.getTime() == 0) {
            return "''";
        }

        return dateFormat.format(date);
    }

    public static String formatDateTime(java.util.Date date) {
        if (date == null || date.getTime() == 0) {
            return "''";
        }

        return dateTimeFormat.format(date);
    }

    public static String formatTime(java.sql.Time time) {
        if (time == null || time.getTime() == 0) {
            return "''";
        }

        return timeFormat.format(time);
    }
    private static SimpleDateFormat dateFormat = new SimpleDateFormat("''yyyy-MM-dd 00:00:00''");
    private static SimpleDateFormat dateTimeFormat = new SimpleDateFormat("''yyyy-MM-dd HH:mm:ss''");
    private static SimpleDateFormat timeFormat = new SimpleDateFormat("''HH:mm:ss''");

    public static String formatBoolean(Boolean value) {
        return value.booleanValue() ? "1" : "0";
    }

    /**
     * Create count(*) query based on existing Builder tables and filters that have already been added to this query
     * @param objectClassName
     * @return
     */
    public int getCount() {
        int count = -1;
        
        Query q = getEntityManager().createQuery(buildQuery(true));
        count = ((Long) q.getSingleResult()).intValue();
        
        return count;
    }
    
    public int getCount(String objectClassName) {
        return getCount(getEntityManager(), objectClassName);
    }
    
    public static int getCount(EntityManager em, String objectClassName) {
        if (em == null) {
            throw new IllegalArgumentException("entityManager cannot be null");
        }

        int count = -1;

        Query q = em.createQuery("SELECT count(*) FROM " + objectClassName);

        count = ((Long) q.getSingleResult()).intValue();

        return count;
    }
    
    public int getCountFiltered(String objectClassName, String fieldName, String filterString, boolean ignoreCase) {
        return getCountFiltered(getEntityManager(), objectClassName, fieldName, filterString, ignoreCase);
    }
    
    public static int getCountFiltered(EntityManager em, String objectClassName, String fieldName, String filterString, boolean ignoreCase) {
        if (em == null) {
            throw new IllegalArgumentException("entityManager cannot be null");
        }

        int count = -1;

        Query q = null;
        if (ignoreCase) {
            q = em.createQuery("SELECT count(*) FROM " + objectClassName + " o WHERE " + QueryUtil.formatLikeClause("o." + fieldName, filterString));
        } else {
            q = em.createQuery("SELECT count(*) FROM " + objectClassName + " o WHERE o." + fieldName + " = '" + filterString + "'");
        }

        count = ((Long) q.getSingleResult()).intValue();

        return count;
    }
    
    public int getCountFiltered(String objectClassName, String fieldName, int filterID) {
        return getCountFiltered(getEntityManager(), objectClassName, fieldName, filterID);
    }
    
    public static int getCountFiltered(EntityManager em, String objectClassName, String fieldName, int filterID) {
        if (em == null) {
            throw new IllegalArgumentException("entityManager cannot be null");
        }

        int count = -1;

        Query q = em.createQuery("SELECT count(*) FROM " + objectClassName + " o WHERE o." + fieldName + " = " + filterID);

        count = ((Long) q.getSingleResult()).intValue();

        return count;
    }
    
    public List<T> findRecordsByValue(String className, String column, int value) {
        JPAQueryBuilder qb = new JPAQueryBuilder(getEntityManager());
        qb.addObject(className);
        qb.addFilter(column, value);
        
        Query q = qb.executeQuery();
        
        List<T> items = null;
        if (q != null) {
            items = q.getResultList();
        }
        
        return items;
    }
    
    public List<T> findRecordsByValue(String className, String column, String value) {
        JPAQueryBuilder qb = new JPAQueryBuilder(getEntityManager());
        qb.addObject(className);
        qb.addFilter(column, value);
        
        Query q = qb.executeQuery();
        
        List<T> items = null;
        if (q != null) {
            items = q.getResultList();
        }
        
        return items;
    }
    
    public T findRecordByValue(String className, String column, int value) {
        JPAQueryBuilder qb = new JPAQueryBuilder(getEntityManager());
        qb.addObject(className);
        qb.addFilter(column, value);
        
        Query q = qb.executeQuery();
        
        T item = null;
        if (q != null) {
            item = (T) q.getSingleResult();
        }
        
        return item;
    }
    
    public T findRecordByValue(String className, String column, String value) {
        JPAQueryBuilder qb = new JPAQueryBuilder(getEntityManager());
        qb.addObject(className);
        qb.addFilter(column, value);
        
        Query q = qb.executeQuery();
        
        T item = null;
        if (q != null) {
            item = (T) q.getSingleResult();
        }
        
        return item;
    }
    
}
