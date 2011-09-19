/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2008, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.as.cmp.jdbc.metadata;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Imutable class which holds all the information jbosscmp-jdbc needs to know
 * about a CMP field It loads its data from standardjbosscmp-jdbc.xml and
 * jbosscmp-jdbc.xml
 *
 * @author <a href="mailto:dain@daingroup.com">Dain Sundstrom</a>
 * @author <a href="sebastien.alborini@m4x.org">Sebastien Alborini</a>
 * @author <a href="mailto:dirk@jboss.de">Dirk Zimmermann</a>
 * @author <a href="mailto:vincent.harcq@hubmethods.com">Vincent Harcq</a>
 * @author <a href="mailto:loubyansky@hotmail.com">Alex Loubyansky</a>
 * @author <a href="mailto:heiko.rupp@cellent.de">Heiko W.Rupp</a>
 * @version $Revision: 81030 $
 */
public final class JDBCCMPFieldMetaData {
    public static final byte CHECK_DIRTY_AFTER_GET_TRUE = 1;
    public static final byte CHECK_DIRTY_AFTER_GET_FALSE = 2;
    public static final byte CHECK_DIRTY_AFTER_GET_NOT_PRESENT = 4;

    /**
     * The entity on which this field is defined.
     */
    private final JDBCEntityMetaData entity;

    /**
     * The name of this field.
     */
    private String fieldName;

    /**
     * The java type of this field
     */
    private Class<?> fieldType;

    /**
     * The column name in the table
     */
    private String columnName;

    /**
     * The jdbc type (see java.sql.Types), used in PreparedStatement.setParameter
     * default value used is intended to cause an exception if used
     */
    private int jdbcType;

    /**
     * The sql type, used for table creation.
     */
    private String sqlType;

    /**
     * Is this field read only?
     */
    private Boolean readOnly;

    /**
     * How long is read valid
     */
    private Integer readTimeOut;

    /**
     * Is this field a memeber of the primary keys or the sole prim-key-field.
     */
    private boolean primaryKeyMember;

    /**
     * Should null values not be allowed for this field.
     */
    private boolean notNull;

    /**
     * Should an index for this field be generated?
     */
    private boolean genIndex;

    /**
     * The Field object in the primary key class for this
     * cmp field, or null if this field is the prim-key-field.
     */
    private Field primaryKeyField;

    /**
     * property overrides
     */
    private List<JDBCCMPFieldPropertyMetaData> propertyOverrides = new ArrayList<JDBCCMPFieldPropertyMetaData>();

    /**
     * indicates whether this is an unknown pk field
     */
    private boolean unknownPkField;

    /**
     * auto-increment flag
     */
    private boolean autoIncrement;

    /**
     * whether this field is a relation table key field
     */
    private boolean relationTableField;

    /**
     * If true, the field should be checked for dirty state after its get method was invoked
     */
    private byte checkDirtyAfterGet = CHECK_DIRTY_AFTER_GET_NOT_PRESENT;

    /**
     * Fully qualified class name of implementation of CMPFieldStateFactory
     */
    private String stateFactory;

//    private static byte readCheckDirtyAfterGet(Element element, byte defaultValue) throws Exception {
//        byte checkDirtyAfterGet;
//        String dirtyAfterGetStr = MetaData.getOptionalChildContent(element, "check-dirty-after-get");
//        if (dirtyAfterGetStr == null) {
//            checkDirtyAfterGet = defaultValue;
//        } else {
//            checkDirtyAfterGet = (Boolean.valueOf(dirtyAfterGetStr).booleanValue() ?
//                    CHECK_DIRTY_AFTER_GET_TRUE : CHECK_DIRTY_AFTER_GET_FALSE);
//        }
//        return checkDirtyAfterGet;
//    }

//    public static byte readCheckDirtyAfterGet(Element element) throws Exception {
//        return readCheckDirtyAfterGet(element, CHECK_DIRTY_AFTER_GET_NOT_PRESENT);
//    }

    /**
     * Constructor for parsing only.
     */
    public JDBCCMPFieldMetaData() {
        this.entity = null;
        jdbcType = Integer.MIN_VALUE;
        sqlType = null;
        primaryKeyMember = true;
        notNull = true;
        genIndex = false;
        unknownPkField = true;
        autoIncrement = false;
        relationTableField = false;
        checkDirtyAfterGet = CHECK_DIRTY_AFTER_GET_NOT_PRESENT;
        stateFactory = null;
    }


    /**
     * This constructor is added especially for unknown primary key field
     */
    public JDBCCMPFieldMetaData(JDBCEntityMetaData entity) {
        this.entity = entity;
        fieldName = entity.getName() + "_upk";
        fieldType = entity.getPrimaryKeyClass();  // java.lang.Object.class
        columnName = entity.getName() + "_upk";
        jdbcType = Integer.MIN_VALUE;
        sqlType = null;
        readTimeOut = null;
        primaryKeyMember = true;
        notNull = true;
        genIndex = false;
        unknownPkField = true;
        autoIncrement = false;
        relationTableField = false;
        checkDirtyAfterGet = CHECK_DIRTY_AFTER_GET_NOT_PRESENT;
        stateFactory = null;
    }

    /**
     * Constructs cmp field meta data for a field on the specified entity with
     * the specified fieldName.
     *
     * @param fieldName name of the field for which the meta data will be loaded
     * @param entity    entity on which this field is defined
     * @throws Exception if data in the entity is inconsistent with field type
     */
    public JDBCCMPFieldMetaData(JDBCEntityMetaData entity, String fieldName) {
        this.entity = entity;
        this.fieldName = fieldName;

        fieldType = loadFieldType(entity, fieldName);

        columnName = fieldName;
        jdbcType = Integer.MIN_VALUE;
        sqlType = null;
        genIndex = false;

        notNull = primaryKeyMember;

        unknownPkField = false;
        autoIncrement = false;
        relationTableField = false;
        checkDirtyAfterGet = CHECK_DIRTY_AFTER_GET_NOT_PRESENT;
        stateFactory = null;

        // initialize primary key info
        String pkFieldName = entity.getPrimaryKeyFieldName();
        if (pkFieldName != null) {
            // single-valued key so field is null

            // is this the pk field
            if (pkFieldName.equals(fieldName)) {
                // verify field type
                if (!entity.getPrimaryKeyClass().equals(fieldType)) {
                    throw new RuntimeException("primkey-field must be the same type as prim-key-class");
                }
                // we are the pk
                primaryKeyMember = true;
            } else {
                primaryKeyMember = false;
            }
        } else {
            // this is a multi-valued key
            Field[] fields = entity.getPrimaryKeyClass().getFields();

            boolean pkMember = false;
            Field pkField = null;
            for (int i = 0; i < fields.length; i++) {
                final Field field = fields[i];
                if (field.getName().equals(fieldName)) {

                    // verify field type
                    if (!field.getType().equals(fieldType)) {
                        throw new RuntimeException("Field " + fieldName + " in prim-key-class must be of the same type.");
                    }

                    if (pkField != null) {
                        if (field.getDeclaringClass().equals(entity.getPrimaryKeyClass())) {
                            pkField = field;
                        }

                        Logger.getLogger(getClass().getName() + '.' + entity.getName()).warn(
                                "PK field " + fieldName + " was found more than once in class hierarchy of " +
                                        entity.getPrimaryKeyClass() + ". Will use the one from " + pkField.getDeclaringClass().getName()
                        );
                    } else {
                        pkField = field;
                    }

                    // we are a pk member
                    pkMember = true;
                }
            }
            primaryKeyMember = pkMember;
            primaryKeyField = pkField;
        }
    }

    public JDBCCMPFieldMetaData(JDBCEntityMetaData entity, JDBCCMPFieldMetaData defaultValues) {
        this.entity = entity;
        fieldName = defaultValues.getFieldName();
        fieldType = defaultValues.getFieldType();
        columnName = defaultValues.getColumnName();
        jdbcType = defaultValues.getJDBCType();
        sqlType = defaultValues.getSQLType();
        primaryKeyMember = defaultValues.isPrimaryKeyMember();
        notNull = defaultValues.isNotNull();
        unknownPkField = defaultValues.isUnknownPkField();
        autoIncrement = defaultValues.isAutoIncrement();
        genIndex = false; // If <dbindex/> is not given on a field, no index is wanted.
        relationTableField = defaultValues.isRelationTableField();
        checkDirtyAfterGet = defaultValues.getCheckDirtyAfterGet();
        stateFactory = defaultValues.getStateFactory();
    }

    /**
     * Constructs a foreign key or a relation table key field.
     */
    public JDBCCMPFieldMetaData(JDBCEntityMetaData entity,
                                JDBCCMPFieldMetaData defaultValues,
                                String columnName,
                                boolean primaryKeyMember,
                                boolean notNull,
                                boolean readOnly,
                                int readTimeOut,
                                boolean relationTableField) {
        this.entity = entity;
        fieldName = defaultValues.getFieldName();
        fieldType = defaultValues.getFieldType();
        this.columnName = columnName;
        jdbcType = defaultValues.getJDBCType();
        sqlType = defaultValues.getSQLType();
        this.readOnly = readOnly;
        this.readTimeOut = readTimeOut;
        this.primaryKeyMember = primaryKeyMember;
        this.notNull = notNull;

        for (JDBCCMPFieldPropertyMetaData propertyMetaData : defaultValues.getPropertyOverrides()) {
            propertyOverrides.add(propertyMetaData);
        }


        this.unknownPkField = defaultValues.isUnknownPkField();
        autoIncrement = false;
        genIndex = false;

        this.relationTableField = relationTableField;
        checkDirtyAfterGet = defaultValues.getCheckDirtyAfterGet();
        stateFactory = defaultValues.getStateFactory();
    }


    /**
     * Gets the entity on which this field is defined
     *
     * @return the entity on which this field is defined
     */
    public JDBCEntityMetaData getEntity() {
        return entity;
    }

    /**
     * Gets the name of the field.
     *
     * @return the name of this field
     */
    public String getFieldName() {
        return fieldName;
    }

    /**
     * Gets the java Class type of this field.
     *
     * @return the Class type of this field
     */
    public Class<?> getFieldType() {
        return fieldType;
    }

    /**
     * Gets the column name the property should use or null if the
     * column name is not overriden.
     *
     * @return the name to which this field is persisted or null if the
     *         column name is not overriden
     */
    public String getColumnName() {
        return columnName;
    }

    /**
     * Gets the JDBC type the property should use or Integer.MIN_VALUE
     * if not overriden.
     *
     * @return the jdbc type of this field
     */
    public int getJDBCType() {
        return jdbcType;
    }

    /**
     * Gets the SQL type the property should use or null
     * if not overriden.
     *
     * @return the sql data type string used in create table statements
     */
    public String getSQLType() {
        return sqlType;
    }

    /**
     * Gets the property overrides.  Property overrides change the default
     * mapping of Dependent Value Object properties. If there are no property
     * overrides this method returns an empty list.
     *
     * @return an unmodifiable list of the property overrides.
     */
    public List<JDBCCMPFieldPropertyMetaData> getPropertyOverrides() {
        return Collections.unmodifiableList(propertyOverrides);
    }

    /**
     * Is this field read only. A read only field will never be persisted
     *
     * @return true if this field is read only
     */
    public boolean isReadOnly() {
        return readOnly != null ? readOnly : entity.isReadOnly();
    }

    /**
     * Gets the length of time (ms) that a read valid or -1 if data must
     * always be reread from the database
     *
     * @return the length of time that data read database is valid, or -1
     *         if data must always be reread from the database
     */
    public int getReadTimeOut() {
        return readTimeOut != null ? readTimeOut : entity.getReadTimeOut();
    }

    /**
     * Is this field one of the primary key fields?
     *
     * @return true if this field is one of the primary key fields
     */
    public boolean isPrimaryKeyMember() {
        return primaryKeyMember;
    }

    /**
     * Should this field allow null values?
     *
     * @return true if this field will not allow a null value.
     */
    public boolean isNotNull() {
        return notNull;
    }

    /**
     * Should an index for this field be generated?
     * Normally this should be false for primary key fields
     * But it seems there are databases that do not automatically
     * put indices on primary keys *sigh*
     *
     * @return true if an index should be generated on this field
     */
    public boolean isIndexed() {
        return genIndex;
    }

    /**
     * Is this field an unknown primary key field?
     *
     * @return true if the field is an unknown primary key field
     */
    public boolean isUnknownPkField() {
        return unknownPkField;
    }

    /**
     * @return true if the key is auto incremented by the database
     */
    public boolean isAutoIncrement() {
        return autoIncrement;
    }

    public boolean isRelationTableField() {
        return relationTableField;
    }

    public byte getCheckDirtyAfterGet() {
        return checkDirtyAfterGet;
    }

    public String getStateFactory() {
        return stateFactory;
    }

    /**
     * Compares this JDBCCMPFieldMetaData against the specified object. Returns
     * true if the objects are the same. Two JDBCCMPFieldMetaData are the same
     * if they both have the same name and are defined on the same entity.
     *
     * @param o the reference object with which to compare
     * @return true if this object is the same as the object argument; false
     *         otherwise
     */
    public boolean equals(Object o) {
        if (o instanceof JDBCCMPFieldMetaData) {
            JDBCCMPFieldMetaData cmpField = (JDBCCMPFieldMetaData) o;
            return fieldName.equals(cmpField.fieldName) &&
                    entity.equals(cmpField.entity);
        }
        return false;
    }

    /**
     * Returns a hashcode for this JDBCCMPFieldMetaData. The hashcode is computed
     * based on the hashCode of the declaring entity and the hashCode of the
     * fieldName
     *
     * @return a hash code value for this object
     */
    public int hashCode() {
        int result = 17;
        result = 37 * result + entity.hashCode();
        result = 37 * result + fieldName.hashCode();
        return result;
    }

    /**
     * Returns a string describing this JDBCCMPFieldMetaData. The exact details
     * of the representation are unspecified and subject to change, but the
     * following may be regarded as typical:
     * <p/>
     * "[JDBCCMPFieldMetaData: fieldName=name,  [JDBCEntityMetaData:
     * entityName=UserEJB]]"
     *
     * @return a string representation of the object
     */
    public String toString() {
        return "[JDBCCMPFieldMetaData : fieldName=" + fieldName + ", " +
                entity + "]";
    }

//    /**
//     * Loads the java type of this field from the entity bean class. If this
//     * bean uses, cmp 1.x persistence, the field type is loaded from the field
//     * in the bean class with the same name as this field. If this bean uses,
//     * cmp 2.x persistence, the field type is loaded from the abstract getter
//     * or setter method for field in the bean class.
//     */
//    private Class loadFieldType(JDBCEntityMetaData entity, String fieldName)
//            throws Exception {
//        if (entity.isCMP1x()) {
//            // CMP 1.x field Style
//            try {
//                return entity.getEntityClassName().getField(fieldName).getType();
//            } catch (NoSuchFieldException e) {
//                throw new Exception("No field named '" + fieldName +
//                        "' found in entity class." +
//                        entity.getEntityClassName().getName());
//            }
//        } else {
//            // CMP 2.x abstract accessor style
//            String baseName = Character.toUpperCase(fieldName.charAt(0)) +
//                    fieldName.substring(1);
//            String getName = "get" + baseName;
//            String setName = "set" + baseName;
//
//            Method[] methods = entity.getEntityClassName().getMethods();
//            for (int i = 0; i < methods.length; i++) {
//                // is this a public abstract method?
//                if (Modifier.isPublic(methods[i].getModifiers()) &&
//                        Modifier.isAbstract(methods[i].getModifiers())) {
//
//                    // get accessor
//                    if (getName.equals(methods[i].getName()) &&
//                            methods[i].getParameterTypes().length == 0 &&
//                            !methods[i].getReturnType().equals(Void.TYPE)) {
//                        return methods[i].getReturnType();
//                    }
//
//                    // set accessor
//                    if (setName.equals(methods[i].getName()) &&
//                            methods[i].getParameterTypes().length == 1 &&
//                            methods[i].getReturnType().equals(Void.TYPE)) {
//
//                        return methods[i].getParameterTypes()[0];
//                    }
//                }
//            }
//            throw new Exception("No abstract accessors for field " +
//                    "named '" + fieldName + "' found in entity class " +
//                    entity.getEntityClassName().getName());
//        }
//    }

    public void setFieldName(final String fieldName) {
        this.fieldName = fieldName;
    }

    public void setReadOnly(final boolean readOnly) {
        this.readOnly = readOnly;
    }

    public void setReadTimeout(final int readTimeOut) {
        this.readTimeOut = readTimeOut;
    }

    public void setColumnName(final String columnName) {
        this.columnName = columnName;
    }

    public void setJdbcType(final int jdbcType) {
        this.jdbcType = jdbcType;
    }

    public void setSqlType(final String sqlType) {
        this.sqlType = sqlType;
    }

    public void setAutoIncrement(final boolean autoIncrement) {
        this.autoIncrement = autoIncrement;
    }

    public void addProperty(final JDBCCMPFieldPropertyMetaData propertyMetaData) {
        propertyOverrides.add(propertyMetaData);
    }

    public void setGenIndex(final boolean genIndex) {
        this.genIndex = genIndex;
    }

    public void setNotNull(final boolean notNull) {
        this.notNull = notNull;
    }

    public void setCheckDirtyAfterGet(final boolean checkDirtyAfterGet) {
        this.checkDirtyAfterGet = checkDirtyAfterGet ? CHECK_DIRTY_AFTER_GET_TRUE : CHECK_DIRTY_AFTER_GET_FALSE;
    }

    public void setStateFactory(final String stateFactory) {
        this.stateFactory = stateFactory;
    }

    public void setFieldType(final Class<?> fieldType) {
        this.fieldType = fieldType;
    }

    public ClassLoader getClassLoader() {
        return JDBCCMPFieldMetaData.class.getClassLoader(); // TODO: this needs to be the deployment CL.
    }

    /**
     * Loads the java type of this field from the entity bean class. If this
     * bean uses, cmp 1.x persistence, the field type is loaded from the field
     * in the bean class with the same name as this field. If this bean uses,
     * cmp 2.x persistence, the field type is loaded from the abstract getter
     * or setter method for field in the bean class.
     */
    private Class loadFieldType(JDBCEntityMetaData entity, String fieldName) {
        if (entity.isCMP1x()) {
            // CMP 1.x field Style
            try {
                return entity.getEntityClass().getField(fieldName).getType();
            } catch (NoSuchFieldException e) {
                throw new RuntimeException("No field named '" + fieldName +
                        "' found in entity class." +
                        entity.getEntityClass().getName());
            }
        } else {
            // CMP 2.x abstract accessor style
            String baseName = Character.toUpperCase(fieldName.charAt(0)) +
                    fieldName.substring(1);
            String getName = "get" + baseName;
            String setName = "set" + baseName;

            Method[] methods = entity.getEntityClass().getMethods();
            for (int i = 0; i < methods.length; i++) {
                // is this a public abstract method?
                if (Modifier.isPublic(methods[i].getModifiers()) &&
                        Modifier.isAbstract(methods[i].getModifiers())) {

                    // get accessor
                    if (getName.equals(methods[i].getName()) &&
                            methods[i].getParameterTypes().length == 0 &&
                            !methods[i].getReturnType().equals(Void.TYPE)) {
                        return methods[i].getReturnType();
                    }

                    // set accessor
                    if (setName.equals(methods[i].getName()) &&
                            methods[i].getParameterTypes().length == 1 &&
                            methods[i].getReturnType().equals(Void.TYPE)) {

                        return methods[i].getParameterTypes()[0];
                    }
                }
            }
            throw new RuntimeException("No abstract accessors for field " +
                    "named '" + fieldName + "' found in entity class " +
                    entity.getEntityClass().getName());
        }
    }


    public Field getPrimaryKeyField() {
        return primaryKeyField;
    }
}
