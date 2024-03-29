/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright © 2011-2012 ForgeRock AS. All rights reserved.
 *
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * http://forgerock.org/license/CDDLv1.0.html
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at http://forgerock.org/license/CDDLv1.0.html
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 */
package org.forgerock.openidm.repo.orientdb.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.forgerock.json.fluent.JsonValue;
import org.forgerock.openidm.config.InvalidException;
import org.forgerock.openidm.objset.ConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.orientechnologies.common.exception.OException;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentPool;
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx;
import com.orientechnologies.orient.core.metadata.schema.OClass;
import com.orientechnologies.orient.core.metadata.schema.OSchema;
import com.orientechnologies.orient.core.metadata.schema.OType;
import com.orientechnologies.orient.core.metadata.security.ORole;
import com.orientechnologies.orient.core.metadata.security.OSecurity;
import com.orientechnologies.orient.core.metadata.security.OUser;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.serialization.compression.impl.ONothingCompression;
import com.orientechnologies.orient.core.storage.OStorage;

/**
 * A Helper to interact with the OrientDB
 * @author aegloff
 */
public class DBHelper {
    final static Logger logger = LoggerFactory.getLogger(DBHelper.class);
    
    private static Map<String, ODatabaseDocumentPool> pools = new HashMap<String, ODatabaseDocumentPool>();

    /**
     * Get the DB pool for the given URL. May return an existing pool instance.
     * Also can initialize/create/update the DB to meet the passed
     * configuration if setupDB is enabled
     * 
     * An existing pool currently does not get resized to the passed min max settings,
     * only newly created pools.
     * 
     * Do not close the returned pool directly as it may be used by others.
     * 
     * To cleanly shut down the application, call closePools at the end
     * 
     * @param dbURL the orientdb URL
     * @param user the orientdb user to connect
     * @param password the orientdb password to connect
     * @param minSize the orientdb pool minimum size
     * @param maxSize the orientdb pool maximum size
     * @param completeConfig the full configuration for the DB
     * @param setupDB true if it should also check the DB exists in the state
     * to match the passed configuration, and to set it up to match 
     * @return the pool
     * @throws org.forgerock.openidm.config.InvalidException
     */
    public synchronized static ODatabaseDocumentPool getPool(String dbURL, String user, String password, 
            int minSize, int maxSize, JsonValue completeConfig, boolean setupDB) throws InvalidException {

        ODatabaseDocumentTx setupDbConn = null;
        ODatabaseDocumentPool pool = null;
        try {
            // Disable Snappy compression as it does not currently work inside
            // OSGi containers. Need to do this here before the DB is created.
            OGlobalConfiguration.STORAGE_COMPRESSION_METHOD.setValue(ONothingCompression.NAME);
       
            if (setupDB) {
                logger.debug("Check DB exists in expected state for pool {}", dbURL);
                setupDbConn = checkDB(dbURL, user, password, completeConfig);
            }
            logger.debug("Getting pool {}", dbURL);
            pool = pools.get(dbURL);

            if (pool == null) {
                pool = initPool(dbURL, user, password, minSize, maxSize, completeConfig);
                pools.put(dbURL, pool);
            }
        } finally {
            if (setupDbConn != null) {
                setupDbConn.close();
            }
        }
        
        return pool;
    }
    
    /**
     * Updates the username and password for the default admin user
     * 
     * @param dbURL the orientdb URL
     * @param oldUser the old orientdb user to update
     * @param oldPassword the old orientdb password to update
     * @param oldUser the new orientdb user
     * @param oldPassword the new orientdb password
     * @throws org.forgerock.openidm.config.InvalidException
     */
    public synchronized static void updateDbCredentials(String dbURL, String oldUser, String oldPassword, 
            String newUser, String newPassword) throws InvalidException {

        ODatabaseDocumentTx db = null;
        try {
            db = new ODatabaseDocumentTx(dbURL);
            db.open(oldUser, oldPassword);
            OSecurity security = db.getMetadata().getSecurity();
            // Delete the old admin user
            security.dropUser(oldUser);
            // Create new admin user with new username and password
            security.createUser(newUser, newPassword, security.getRole(ORole.ADMIN));
        } catch (Exception e) {
            logger.error("Error updating DB credentials", e);
        } finally {
            if (db != null) {
                db.close();
            }
        }
    }
    
    /**
     * Closes all pools managed by this helper
     * Call at application shut-down to cleanly shut down the pools.
     */
    public synchronized static void closePools() {
        logger.debug("Close DB pools");
        for (ODatabaseDocumentPool pool : pools.values()) {
            try {
                pool.close();
                logger.trace("Closed pool {}", pool);
            } catch (Exception ex) {
                logger.info("Faillure reported in closing pool {}", pool, ex);
            }
        }
        pools = new HashMap(); // release all our closed pool references
    }
    
    /**
     * Close and remove a pool managed by this helper
     */
    public synchronized static void closePool(String dbUrl, ODatabaseDocumentPool pool) {
        logger.debug("Close DB pool for {} {}", dbUrl, pool);
        try {
            pools.remove(dbUrl);
            pool.close();
            logger.trace("Closed pool for {} {}", dbUrl, pool);
        } catch (Exception ex) {
            logger.info("Failure reported in closing pool {} {}", new Object[] {dbUrl, pool, ex});
        }
    }
    
    /**
     * Initialize the DB pool.
     * @param dbURL the orientdb URL
     * @param user the orientdb user to connect
     * @param password the orientdb password to connect
     * @param minSize the orientdb pool minimum size
     * @param maxSize the orientdb pool maximum size
     * @param completeConfig
     * @return the initialized pool
     * @throws org.forgerock.openidm.config.InvalidException
     */
    private static ODatabaseDocumentPool initPool(String dbURL, String user, String password, 
            int minSize, int maxSize, JsonValue completeConfig) throws InvalidException {
        logger.trace("Initializing DB Pool {}", dbURL);
        
        // Enable transaction log
        OGlobalConfiguration.TX_USE_LOG.setValue(true);
        
        // Conservative defaults to immediately disk sync.
        // Can be relaxed via config for reliable (RAID) hardware
        
        // Immediate disk sync for every record operation, OrientDB setting for nonTX.recordUpdate.synch
        boolean nonTxRecordUpdateSync = completeConfig.get("nonTransactionRecordUpdateSync")
                .defaultTo(Boolean.TRUE).asBoolean();
        OGlobalConfiguration.NON_TX_RECORD_UPDATE_SYNCH.setValue(nonTxRecordUpdateSync);
        
        // Immediate disk sync for each transaction log, OrientDB setting for tx.log.sync
        boolean txLogSync = completeConfig.get("transactionLogSynch").defaultTo(Boolean.TRUE).asBoolean();
        OGlobalConfiguration.TX_LOG_SYNCH.setValue(txLogSync);
        
        // Immediate disk sync for commit, OrientDB setting for tx.commit.sync
        boolean txCommitSync = completeConfig.get("transactionCommitSynch").defaultTo(Boolean.TRUE).asBoolean();
        OGlobalConfiguration.TX_COMMIT_SYNCH.setValue(txCommitSync);

        // Have the storage closed when the DB is closed.
        OGlobalConfiguration.STORAGE_KEEP_OPEN.setValue(false);
        
        boolean success = false;
        int maxRetry = 10;
        int retryCount = 0;
        ODatabaseDocumentPool pool = null;
        
        // Initialize and try to verify the DB. Retry maxRetry times.
        do {
            retryCount++;
            if (pool != null) {
                pool.close();
            }
            // Use our own sized pool, rather than the global one
            // pool = ODatabaseDocumentPool.global();
            pool = new ODatabaseDocumentPool();
            pool.setup(minSize, maxSize);
            warmUpPool(pool, dbURL, user, password, 1);
            
            boolean finalTry = (retryCount >= maxRetry);
            success = test(pool, dbURL, user, password, finalTry);
        } while (!success && retryCount < maxRetry);
        
        if (!success) {
            logger.warn("DB could not be verified.");
        } else {
            logger.info("DB verified on try {}", retryCount);
        }
        
        logger.debug("Opened and initialized pool {}", pool);

        return pool;
    }
    
    /**
     * Perform a basic access on the DB for a rudimentary test 
     * @return whether the basic access succeeded
     */
    private static boolean test(ODatabaseDocumentPool pool, String dbURL, String user,
            String password, boolean finalTry) {
        
        ODatabaseDocumentTx db = null;
        try {
            logger.info("Verifying the DB.");
            db = pool.acquire(dbURL, user, password);
            java.util.Iterator iter = db.browseClass("config"); // Config always should exist
            if (iter.hasNext()) {
                iter.next();
            }
        } catch (OException ex) {
            if (finalTry) {
                logger.info("Exceptions encountered in verifying the DB", ex);
            } else {
                logger.debug("DB exception in testing.", ex);
            }
            return false;
        } finally {
            if (db != null) {
                db.close();
            } 
        }
        return true;
    }
    
    /**
     * Ensure the min size pool entries are initilized.
     * Cuts down on some (small) initial latency with lazy init
     * Do not call with a min past the real pool max, it will block.
     */
    private static void warmUpPool(ODatabaseDocumentPool pool, String dbURL, String user,
            String password, int minSize) {
        
        logger.trace("Warming up pool up to minSize {}", Integer.valueOf(minSize));
        List<ODatabaseDocumentTx> list = new ArrayList<ODatabaseDocumentTx>();
        for (int count=0; count < minSize; count++) {
            logger.trace("Warming up entry {}", Integer.valueOf(count));
            try {
                list.add(pool.acquire(dbURL, user, password));
            } catch (Exception ex) {
                logger.warn("Issue in warming up db pool, entry {}", Integer.valueOf(count), ex);
            }
        }
        for (ODatabaseDocumentTx entry : list) {
            try {
                if (entry != null) {
                    entry.close();
                }
            } catch (Exception ex) {
                logger.warn("Issue in connection close during warming up db pool, entry {}", entry, ex);
            }
        }
    }
    
    /**
     * Ensures the DB is present in the expected form.
     * @return the db reference. The caller MUST reliably close that DB when done with it, e.g. in a finally.
     * Be aware that an in-memory DB will disappear if there is no connection open to it,
     * and the keep open setting is not explicitly set to true
     */
    private static ODatabaseDocumentTx checkDB(String dbURL, String user, String password, JsonValue completeConfig) 
            throws InvalidException {
        
        // TODO: Creation/opening of db may be not be necessary if we require this managed externally
        ODatabaseDocumentTx db = new ODatabaseDocumentTx(dbURL);
        boolean dbExists = false;

        // To add support for remote DB checking/creation one 
        // would need to use OServerAdmin instead
        // boolean dbExists = new OServerAdmin(dbURL).connect(user, password).existsDatabase();

        // Local DB we can auto populate 
        if (isLocalDB(dbURL) || isMemoryDB(dbURL)) {
            if (db.exists()) {
                logger.info("Using DB at {}", dbURL);
                // Make sure the db is closed
                db.close();
                // open the db
                db.open(user, password); 
                populateSample(db, completeConfig);
            } else { 
                logger.info("DB does not exist, creating {}", dbURL);
                db.create();
                // Delete default admin user
                OSecurity security = db.getMetadata().getSecurity();
                security.dropUser(OUser.ADMIN);
                // Create new admin user with new username and password
                security.createUser(user, password, security.getRole(ORole.ADMIN));
                populateSample(db, completeConfig);
            } 
        } else {
            logger.info("Using remote DB at {}", dbURL);
        }
        return db;
    }
    
    /**
     * Whether the URL represents a local DB
     * @param dbURL the OrientDB db url
     * @return true if local, false if remote
     * @throws InvalidException if the dbURL is null or otherwise known to be invalid
     */
    public static boolean isLocalDB(String dbURL) throws InvalidException {
    	if (dbURL == null) {
    		throw new InvalidException("dbURL is not set");
    	}
    	return dbURL.startsWith("local:") || dbURL.startsWith("plocal");
    }

    /**
     * Whether the URL represents a memory DB
     * @param dbURL the OrientDB db url
     * @return true if local, false if remote
     * @throws InvalidException if the dbURL is null or otherwise known to be invalid
     */
    public static boolean isMemoryDB(String dbURL) throws InvalidException {
    	if (dbURL == null) {
    		throw new InvalidException("dbURL is not set");
    	}
    	return dbURL.startsWith("memory:");
    }
    
    // TODO: Review the initialization mechanism
    private static void populateSample(ODatabaseDocumentTx db, JsonValue completeConfig) 
            throws InvalidException {
        
        JsonValue dbStructure = completeConfig.get(OrientDBRepoService.CONFIG_DB_STRUCTURE);
        if (dbStructure == null) {
            logger.warn("No database structure defined in the configuration." + completeConfig);
        } else {
            JsonValue orientDBClasses = dbStructure.get(OrientDBRepoService.CONFIG_ORIENTDB_CLASS);
            OSchema schema = db.getMetadata().getSchema();
            
            // Default always to create Config class for bootstrapping
            if (orientDBClasses == null || orientDBClasses.isNull()) {
                orientDBClasses = new JsonValue(new java.util.HashMap());
            }
            
            Map cfgIndexes = new java.util.HashMap();
            orientDBClasses.put("config", cfgIndexes);
                            
            logger.info("Setting up database");
            if (orientDBClasses != null) {
            for (Object key : orientDBClasses.keys()) {
                String orientClassName = (String) key;
                JsonValue orientClassConfig = (JsonValue) orientDBClasses.get(orientClassName);
                if (schema.existsClass(orientClassName)) {
                        logger.trace("OrientDB class {} already exists, checking indexes", orientClassName);
                        createOrienDBClassIndexes(schema, orientClassName, orientClassConfig);
                } else {
                    createOrientDBClass(db,schema, orientClassName, orientClassConfig);
                    if ("internal_user".equals(orientClassName)) {
                        populateDefaultUsers(orientClassName, db, completeConfig);
                    }
                }
            }
            }
            schema.save(); 
            
        }
    }
    
    private static void createOrientDBClass(ODatabaseDocumentTx db, OSchema schema,
            String orientClassName, JsonValue orientClassConfig) {
        
        logger.info("Creating OrientDB class {}", orientClassName);
        OClass orientClass = schema.createClass(orientClassName, 
                db.addCluster(orientClassName, 
                OStorage.CLUSTER_TYPE.PHYSICAL));
        
        JsonValue indexes = orientClassConfig.get(OrientDBRepoService.CONFIG_INDEX);
        for (JsonValue index : indexes) {
            String propertyType = index.get(OrientDBRepoService.CONFIG_PROPERTY_TYPE).asString();
            String indexType = index.get(OrientDBRepoService.CONFIG_INDEX_TYPE).asString();
            
            String propertyName = index.get(OrientDBRepoService.CONFIG_PROPERTY_NAME).asString();
            String[] propertyNames = null; 
            if (propertyName != null) {
                propertyNames = new String[] {propertyName};
            } else {
                List propNamesList = index.get(OrientDBRepoService.CONFIG_PROPERTY_NAMES).asList();
                if (propNamesList == null) {
                    throw new InvalidException("Invalid index configuration. " 
                            + "Missing property name(s) on index configuration for property type "
                            + propertyType + " with index type " + indexType + " on " + orientClassName);
                }
                propertyNames = (String[]) propNamesList.toArray(new String[0]);
            }

            // Check if a single property is being defined and create it if so
            for (String propName : propertyNames) {
                if (orientClass.getProperty(propName) != null) {
                    continue;
                }
                logger.info("Creating property {} of type {}", new Object[] {propName, propertyType});
                OType orientPropertyType = null;
                try {
                    // Create property type object
                    orientPropertyType = OType.valueOf(propertyType.toUpperCase());
                } catch (IllegalArgumentException ex) {
                    throw new InvalidException("Invalid property type '"
                            + propertyType + "' in configuration on properties "
                            + propertyNames + " with index type " + indexType
                            + " on " + orientClassName + " valid values: " + OType.values() 
                            + " failure message: " + ex.getMessage(), ex);
                }
                // Create property
                orientClass.createProperty(propName, orientPropertyType);
            }
            createIndex(orientClass, indexType, propertyNames, propertyType);
        }
    }
    
    // Populates the default user, the pwd needs to be changed by the installer
    private static void populateDefaultUsers(String defaultTableName, ODatabaseDocumentTx db, 
            JsonValue completeConfig) throws InvalidException {
        
        String defaultAdminUser = "openidm-admin";
        // Default password needs to be replaced after installation
        String defaultAdminPwd = "openidm-admin";
        String defaultAdminRoles = "openidm-admin,openidm-authorized";
        populateDefaultUser(defaultTableName, db, completeConfig, defaultAdminUser, 
                defaultAdminPwd, defaultAdminRoles);
        logger.trace("Created default user {}. Please change the assigned default password.", 
                defaultAdminUser);
        
        String anonymousUser = "anonymous";
        String anonymousPwd = "anonymous";
        String anonymousRoles = "openidm-reg";
        populateDefaultUser(defaultTableName, db, completeConfig, anonymousUser, anonymousPwd, anonymousRoles);
        logger.trace("Created default user {} for registration purposes.", anonymousUser);
    }    
    
    private static void populateDefaultUser(String defaultTableName, ODatabaseDocumentTx db, 
            JsonValue completeConfig, String user, String pwd, String roles) throws InvalidException {        
        
        JsonValue defaultAdmin = new JsonValue(new HashMap<String, Object>());
        defaultAdmin.put("_openidm_id", user);
        defaultAdmin.put("userName", user);
        defaultAdmin.put("password", pwd);
        defaultAdmin.put("roles", roles);
        
        try {
            ODocument newDoc = DocumentUtil.toDocument(defaultAdmin.asMap(), null, db, defaultTableName);
            newDoc.save();
        } catch (ConflictException ex) {
            throw new InvalidException("Unexpected failure during DB set-up of default user", ex);
        }
    }
    
    private static String uniqueIndexName(String orientClassName, String[] propertyNames) {
        // Determine a unique name to use for the index
        // Naming pattern used is <class>!property1[!propertyN]*!Idx
        StringBuilder sb = new StringBuilder(orientClassName);
        sb.append("!");
        for (String entry : propertyNames) {
            sb.append(entry);
            sb.append("!");
        }
        sb.append("Idx");
        return sb.toString();
    }
    
    private static void createIndex(OClass orientClass, String indexType, String[] propertyNames, String propertyType) {
            logger.info("Creating index on properties {} of type {} with index type {} on {} for OrientDB class ", 
                    new Object[] {propertyNames, propertyType, indexType, orientClass.getName()});
            try {
                // Create the index
                String indexName = uniqueIndexName(orientClass.getName(), propertyNames);
                OClass.INDEX_TYPE orientIndexType = OClass.INDEX_TYPE.valueOf(indexType.toUpperCase());
                orientClass.createIndex(indexName, orientIndexType, propertyNames);
            } catch (IllegalArgumentException ex) {
                throw new InvalidException("Invalid index type '" + indexType + 
                        "' in configuration on properties "
                        + propertyNames + " of type " + propertyType + " on " 
                        + orientClass.getName() + " valid values: " + OClass.INDEX_TYPE.values() 
                        + " failure message: " + ex.getMessage(), ex);
            }
    }

    private static void createOrienDBClassIndexes(OSchema schema, String orientClassName, JsonValue orientClassConfig) {
        logger.info("Checking indexes for OrientDB class {}", orientClassName);
        OClass orientClass = schema.getClass(orientClassName);
        if (orientClass == null) {
            // Should be impossible
            throw new InvalidException("Invalid index configuration. " 
                    + "Index defined for  " + orientClassName + " which does not exist");
        } else {
            JsonValue indexes = orientClassConfig.get(OrientDBRepoService.CONFIG_INDEX);
            for (JsonValue index : indexes) {
                String propertyType = index.get(OrientDBRepoService.CONFIG_PROPERTY_TYPE).asString();
                String indexType = index.get(OrientDBRepoService.CONFIG_INDEX_TYPE).asString();

                String propertyName = index.get(OrientDBRepoService.CONFIG_PROPERTY_NAME).asString();
                String[] propertyNames = null; 
                if (propertyName != null) {
                    propertyNames = new String[] {propertyName};
                } else {
                    List propNamesList = index.get(OrientDBRepoService.CONFIG_PROPERTY_NAMES).asList();
                    if (propNamesList == null) {
                        throw new InvalidException("Invalid index configuration. " 
                                + "Missing property name(s) on index configuration for property type "
                                + propertyType + " with index type " + indexType + " on " + orientClassName);
                    }
                    propertyNames = (String[]) propNamesList.toArray(new String[0]);
                }
                
                String indexName = uniqueIndexName(orientClass.getName(), propertyNames);
                if (orientClass.getClassIndex(indexName) != null) {
                    logger.trace("Index {} already exists for properties {} of type {} with index type"
                            + " {} on {} for OrientDB class, skipping",
                            new Object[] {indexName, propertyNames, propertyType, indexType, orientClass.getName()});
                } else {
                    createIndex(orientClass, indexType, propertyNames, propertyType);
                }
            }
        }
    }
}
