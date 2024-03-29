/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright © 2011-2013 ForgeRock AS. All rights reserved.
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

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.ConfigurationPolicy;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.ReferencePolicy;
import org.apache.felix.scr.annotations.Service;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Modified;
import org.forgerock.json.fluent.JsonException;
import org.forgerock.json.fluent.JsonValue;
import org.forgerock.openidm.config.EnhancedConfig;
import org.forgerock.openidm.config.JSONEnhancedConfig;
import org.forgerock.openidm.core.IdentityServer;
import org.forgerock.openidm.repo.QueryConstants;
import org.forgerock.openidm.repo.RepoBootService;
import org.forgerock.openidm.repo.RepositoryService; 
import org.forgerock.openidm.repo.orientdb.impl.query.PredefinedQueries;
import org.forgerock.openidm.repo.orientdb.impl.query.Queries;

import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx;
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentPool;
import com.orientechnologies.orient.core.exception.ODatabaseException;
import com.orientechnologies.orient.core.exception.OConcurrentModificationException;
import com.orientechnologies.orient.core.index.OIndexException;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.storage.ORecordDuplicatedException;

// JSON Resource
import org.forgerock.json.resource.JsonResource;

// Deprecated
import org.forgerock.openidm.objset.BadRequestException;
import org.forgerock.openidm.objset.ConflictException;
import org.forgerock.openidm.objset.ForbiddenException;
import org.forgerock.openidm.objset.InternalServerErrorException;
import org.forgerock.openidm.objset.JsonResourceObjectSet;
import org.forgerock.openidm.objset.NotFoundException;
import org.forgerock.openidm.objset.ObjectSet;
import org.forgerock.openidm.objset.ObjectSetException;
import org.forgerock.openidm.objset.ObjectSetJsonResource;
import org.forgerock.openidm.objset.Patch;
import org.forgerock.openidm.objset.PreconditionFailedException;

/**
 * Repository service implementation using OrientDB
 * @author aegloff
 */
@Component(name = OrientDBRepoService.PID, immediate=true, policy=ConfigurationPolicy.REQUIRE, enabled=true)
@Service (value = {RepositoryService.class, JsonResource.class}) // Omit the RepoBootService interface from the managed service
@Properties({
    @Property(name = "service.description", value = "Repository Service using OrientDB"),
    @Property(name = "service.vendor", value = "ForgeRock AS"),
    @Property(name = "openidm.router.prefix", value = "repo"),
    @Property(name = "db.type", value = "OrientDB")
})
public class OrientDBRepoService extends ObjectSetJsonResource implements RepositoryService, RepoBootService {
    final static Logger logger = LoggerFactory.getLogger(OrientDBRepoService.class);

    public static final String PID = "org.forgerock.openidm.repo.orientdb";
    
    // Keys in the JSON configuration
    public static final String CONFIG_QUERIES = "queries";
    public static final String CONFIG_DB_URL = "dbUrl";
    public static final String CONFIG_USER = "user";
    public static final String CONFIG_PASSWORD = "password";
    public static final String CONFIG_POOL_MIN_SIZE = "poolMinSize";
    public static final String CONFIG_POOL_MAX_SIZE = "poolMaxSize";
    public static final String CONFIG_DB_STRUCTURE = "dbStructure";
    public static final String CONFIG_ORIENTDB_CLASS = "orientdbClass";
    public static final String CONFIG_INDEX = "index";
    public static final String CONFIG_PROPERTY_NAME = "propertyName";
    public static final String CONFIG_PROPERTY_NAMES = "propertyNames";
    public static final String CONFIG_PROPERTY_TYPE = "propertyType";
    public static final String CONFIG_INDEX_TYPE = "indexType";
    
    public static final String ACTION_UPDATE_CREDENTIALS = "updateDbCredentials";
    
    // Default settings for pool size min and max
    public static final int DEFAULT_POOL_MIN_SIZE = 5;
    public static final int DEFAULT_POOL_MAX_SIZE = 20;
    
    // Used to synchronize 
    private static Object dbLock = new Object();
    
    private static OrientDBRepoService bootRepo = null;
    
    ODatabaseDocumentPool pool;

    String dbURL; 
    String user;
    String password;
    int poolMinSize;
    int poolMaxSize;

    // Current configuration
    JsonValue existingConfig;
    
    // TODO: evaluate use of Guice instead
    PredefinedQueries predefinedQueries = new PredefinedQueries();
    Queries queries = new Queries();
    EnhancedConfig enhancedConfig = new JSONEnhancedConfig();

    EmbeddedOServerService embeddedServer;
    
    @Reference(
            name = "ref_OrientDBRepoService_ConfigObjectService",
            referenceInterface = JsonResource.class,
            bind = "bindConfigService",
            unbind = "unbindConfigService",
            cardinality = ReferenceCardinality.MANDATORY_UNARY,
            policy = ReferencePolicy.DYNAMIC,
            target = "(service.pid=org.forgerock.openidm.config)"
        )
        protected ObjectSet configService;
        protected void bindConfigService(JsonResource configService) {
            this.configService = new JsonResourceObjectSet(configService);
        }
        protected void unbindConfigService(JsonResource configService) {
            this.configService = null;
        }
    
    /**
     * Gets an object from the repository by identifier. The returned object is not validated 
     * against the current schema and may need processing to conform to an updated schema.
     * <p>
     * The object will contain metadata properties, including object identifier {@code _id},
     * and object version {@code _rev} to enable optimistic concurrency supported by OrientDB and OpenIDM.
     *
     * @param fullId the identifier of the object to retrieve from the object set.
     * @throws NotFoundException if the specified object could not be found. 
     * @throws ForbiddenException if access to the object is forbidden.
     * @throws BadRequestException if the passed identifier is invalid
     * @return the requested object.
     */
    @Override
    public Map<String, Object> read(String fullId) throws ObjectSetException {
        String localId = getLocalId(fullId);
        String type = getObjectType(fullId);
        
        if (fullId == null || localId == null) {
            throw new NotFoundException("The repository requires clients to supply an identifier for the object to create. Full identifier: " + fullId + " local identifier: " + localId);
        } else if (type == null) {
            throw new NotFoundException("The object identifier did not include sufficient information to determine the object type: " + fullId);
        }
        
        ODatabaseDocumentTx db = getConnection();
        Map<String, Object> result = null;
        try {
            ODocument doc = predefinedQueries.getByID(localId, type, db);
            if (doc == null) {
                throw new NotFoundException("Object " + localId + " not found in " + type);
            }
            result = DocumentUtil.toMap(doc);
            logger.trace("Completed get for id: {}, type: {}, result: {}", localId, type, result);        
        } finally {
            if (db != null) {
                db.close();
            }
        }

        return result;
    }

    /**
     * Creates a new object in the object set.
     * <p>
     * This method sets the {@code _id} property to the assigned identifier for the object,
     * and the {@code _rev} property to the revised object version (For optimistic concurrency)
     *
     * @param fullId the client-generated identifier to use, or {@code null} if server-generated identifier is requested.
     * @param obj the contents of the object to create in the object set.
     * @throws NotFoundException if the specified id could not be resolved. 
     * @throws ForbiddenException if access to the object or object set is forbidden.
     * @throws PreconditionFailedException if an object with the same ID already exists.
     */
    @Override
    public void create(String fullId, Map<String, Object> obj) throws ObjectSetException {
        String localId = getLocalId(fullId);
        String type = getObjectType(fullId);
        String orientClassName = typeToOrientClassName(type);
 
        if (fullId == null || localId == null) {
            throw new NotFoundException("The repository requires clients to supply an identifier for the object to create. Full identifier: " + fullId + " local identifier: " + localId);
        } else if (type == null) {
            throw new NotFoundException("The object identifier did not include sufficient information to determine the object type: " + fullId);
        }
        
        obj.put(DocumentUtil.TAG_ID, localId);
        
        ODatabaseDocumentTx db = getConnection();
        try{
            // Rather than using MVCC for insert, rely on primary key uniqueness constraints to detect duplicate create
            ODocument newDoc = DocumentUtil.toDocument(obj, null, db, orientClassName);
            logger.trace("Created doc for id: {} to save {}", fullId, newDoc);
            newDoc.save();
            
            obj.put(DocumentUtil.TAG_REV, Integer.toString(newDoc.getVersion()));
            logger.debug("Completed create for id: {} revision: {}", fullId, newDoc.getVersion());
            logger.trace("Create payload for id: {} doc: {}", fullId, newDoc);
        } catch (ORecordDuplicatedException ex) {
            // Because the OpenIDM ID is defined as unique, duplicate inserts must fail
            throw new PreconditionFailedException("Create rejected as Object with same ID already exists. " + ex.getMessage(), ex);
        } catch (OIndexException ex) {
            // Because the OpenIDM ID is defined as unique, duplicate inserts must fail
            throw new PreconditionFailedException("Create rejected as Object with same ID already exists. " + ex.getMessage(), ex);
        } catch (ODatabaseException ex) {
            // Because the OpenIDM ID is defined as unique, duplicate inserts must fail. 
            // OrientDB may wrap the IndexException root cause.
            if (isCauseIndexException(ex, 10) || isCauseRecordDuplicatedException(ex, 10)) {
                throw new PreconditionFailedException("Create rejected as Object with same ID already exists and was detected. " 
                        + ex.getMessage(), ex);
            } else {
                throw ex;
            }
        } catch (RuntimeException e){
            throw e;
        } finally {
            if (db != null) {
                db.close();
            }
        }
    }
    
    /**
     * Updates the specified object in the object set. 
     * <p>
     * This implementation requires MVCC and hence enforces that clients state what revision they expect 
     * to be updating
     * 
     * If successful, this method updates metadata properties within the passed object,
     * including: a new {@code _rev} value for the revised object's version
     *
     * @param fullId the identifier of the object to be put, or {@code null} to request a generated identifier.
     * @param rev the version of the object to update; or {@code null} if not provided.
     * @param obj the contents of the object to put in the object set.
     * @throws ConflictException if version is required but is {@code null}.
     * @throws ForbiddenException if access to the object is forbidden.
     * @throws NotFoundException if the specified object could not be found. 
     * @throws PreconditionFailedException if version did not match the existing object in the set.
     * @throws BadRequestException if the passed identifier is invalid
     */
    @Override
    public void update(String fullId, String rev, Map<String, Object> obj) throws ObjectSetException {
        
        String localId = getLocalId(fullId);
        String type = getObjectType(fullId);
        String orientClassName = typeToOrientClassName(type);
        
        if (rev == null) {
            throw new ConflictException("Object passed into update does not have revision it expects set.");
        } else {
            obj.put(DocumentUtil.TAG_REV, rev);
        }
        
        ODatabaseDocumentTx db = getConnection();
        try{
            ODocument existingDoc = predefinedQueries.getByID(localId, type, db);
            if (existingDoc == null) {
                throw new NotFoundException("Update on object " + fullId + " could not find existing object.");
            }
            ODocument updatedDoc = DocumentUtil.toDocument(obj, existingDoc, db, orientClassName);
            logger.trace("Updated doc for id {} to save {}", fullId, updatedDoc);
            
            updatedDoc.save();

            obj.put(DocumentUtil.TAG_REV, Integer.toString(updatedDoc.getVersion()));
            // Set ID to return to caller
            obj.put(DocumentUtil.TAG_ID, updatedDoc.field(DocumentUtil.ORIENTDB_PRIMARY_KEY));
            logger.debug("Committed update for id: {} revision: {}", fullId, updatedDoc.getVersion());
            logger.trace("Update payload for id: {} doc: {}", fullId, updatedDoc);
        } catch (ODatabaseException ex) {
        	// Without transaction the concurrent modification exception gets nested instead
        	if (isCauseConcurrentModificationException(ex, 10)) {
        		throw new PreconditionFailedException(
                        "Update rejected as current Object revision is different than expected by caller, the object has changed since retrieval: " 
                        + ex.getMessage(), ex);
        	} else {
        		throw ex;
        	}
        } catch (OConcurrentModificationException ex) {
            throw new PreconditionFailedException(
                    "Update rejected as current Object revision is different than expected by caller, the object has changed since retrieval: " 
                    + ex.getMessage(), ex);
        } catch (RuntimeException e){
            throw e;
        } finally {
            if (db != null) {
                db.close();
            } 
        }
    }

    /**
     * Deletes the specified object from the object set.
     *
     * @param fullId the identifier of the object to be deleted.
     * @param rev the version of the object to delete or {@code null} if not provided.
     * @throws NotFoundException if the specified object could not be found. 
     * @throws ForbiddenException if access to the object is forbidden.
     * @throws ConflictException if version is required but is {@code null}.
     * @throws PreconditionFailedException if version did not match the existing object in the set.
     */
    @Override
    public void delete(String fullId, String rev) throws ObjectSetException {
        String localId = getLocalId(fullId);
        String type = getObjectType(fullId);

        if (rev == null) {
            throw new ConflictException("Object passed into delete does not have revision it expects set.");
        } 
        
        int ver = DocumentUtil.parseVersion(rev); // This throws ConflictException if parse fails
        
        ODatabaseDocumentTx db = getConnection();
        try {
            ODocument existingDoc = predefinedQueries.getByID(localId, type, db);
            if (existingDoc == null) {
                throw new NotFoundException("Object does not exist for delete on: " + fullId);
            }
            
            existingDoc.setVersion(ver); // State the version we expect to delete for MVCC check

            db.delete(existingDoc); 
            logger.debug("delete for id succeeded: {} revision: {}", localId, rev);
        } catch (ODatabaseException ex) {
        	// Without transaction the concurrent modification exception gets nested instead
        	if (isCauseConcurrentModificationException(ex, 10)) {
        		throw new PreconditionFailedException(
                        "Delete rejected as current Object revision is different than expected by caller, the object has changed since retrieval. "
                        + ex.getMessage(), ex);
        	} else {
        		throw ex;
        	}
        } catch (OConcurrentModificationException ex) {  
            throw new PreconditionFailedException(
                    "Delete rejected as current Object revision is different than expected by caller, the object has changed since retrieval."
                    + ex.getMessage(), ex);
        } catch (RuntimeException e){
            throw e;
        } finally {
            if (db != null) {
                db.close();
            }
        }
    }

    /**
     * Currently not supported by this implementation.
     * 
     * Applies a patch (partial change) to the specified object in the object set.
     *
     * @param id the identifier of the object to be patched.
     * @param rev the version of the object to patch or {@code null} if not provided.
     * @param patch the partial change to apply to the object.
     * @throws ConflictException if patch could not be applied object state or if version is required.
     * @throws ForbiddenException if access to the object is forbidden.
     * @throws NotFoundException if the specified object could not be found. 
     * @throws PreconditionFailedException if version did not match the existing object in the set.
     */
    @Override
    public void patch(String id, String rev, Patch patch) throws ObjectSetException {
        throw new UnsupportedOperationException();
    }

    /**
     * Performs the query on the specified object and returns the associated results.
     * <p>
     * Queries are parametric; a set of named parameters is provided as the query criteria.
     * The query result is a JSON object structure composed of basic Java types. 
     * 
     * The returned map is structured as follow: 
     * - The top level map contains meta-data about the query, plus an entry with the actual result records.
     * - The <code>QueryConstants</code> defines the map keys, including the result records (QUERY_RESULT)
     *
     * @param fullId identifies the object to query.
     * @param params the parameters of the query to perform.
     * @return the query results, which includes meta-data and the result records in JSON object structure format.
     * @throws NotFoundException if the specified object could not be found. 
     * @throws BadRequestException if the specified params contain invalid arguments, e.g. a query id that is not
     * configured, a query expression that is invalid, or missing query substitution tokens.
     * @throws ForbiddenException if access to the object or specified query is forbidden.
     */
    @Override
    public Map<String, Object> query(String fullId, Map<String, Object> params) throws ObjectSetException {
        // TODO: replace with common utility
        String type = fullId; 
        // This should not be necessary as relative URI should not start with slash
        if (fullId != null && fullId.startsWith("/")) {
            type = fullId.substring(1);
        }
        logger.trace("Full id: {} Extracted type: {}", fullId, type);
        
        Map<String, Object> result = new HashMap<String, Object>();
        ODatabaseDocumentTx db = getConnection();
        try {
            List<Map<String, Object>> docs = new ArrayList<Map<String, Object>>();
            result.put(QueryConstants.QUERY_RESULT, docs);
            long start = System.currentTimeMillis();
            List<ODocument> queryResult = queries.query(type, params, db); 
            long end = System.currentTimeMillis();
            if (queryResult != null) {
                long convStart = System.currentTimeMillis();
                for (ODocument entry : queryResult) {
                    Map<String, Object> convertedEntry = DocumentUtil.toMap(entry);
                    docs.add(convertedEntry);
                }
                long convEnd = System.currentTimeMillis();
                result.put(QueryConstants.STATISTICS_CONVERSION_TIME, Long.valueOf(convEnd-convStart));
            }
            result.put(QueryConstants.STATISTICS_QUERY_TIME, Long.valueOf(end-start));
            
            if (logger.isDebugEnabled()) {
                logger.debug("Query result contains {} records, took {} ms and took {} ms to convert result.",
                        new Object[] {((List) result.get(QueryConstants.QUERY_RESULT)).size(),
                        result.get(QueryConstants.STATISTICS_QUERY_TIME),
                        result.get(QueryConstants.STATISTICS_CONVERSION_TIME)});
            }
        } finally {
            if (db != null) {
                db.close();
            }
        }
        
        return result;
    }

    @Override
    public Map<String, Object> action(String id, Map<String, Object> params) throws ObjectSetException {
        if (params.get("_action") == null) {
            throw new BadRequestException("Expecting _action parameter");
        }

        String action = (String)params.get("_action");
        try {
            if (ACTION_UPDATE_CREDENTIALS.equals(action)) {
                String newUser = (String)params.get("user");
                String newPassword = (String)params.get("password");
                if (newUser == null || newPassword == null) {
                    throw new BadRequestException("Expecting 'user' and 'password' parameters");
                }
                synchronized (dbLock) {
                    DBHelper.updateDbCredentials(dbURL, user, password, newUser, newPassword);
                    JsonValue config = new JsonValue(configService.read(PID));
                    config.put("user", newUser);
                    config.put("password", newPassword);
                    configService.update(PID, null, config.asMap());
                    return params;
                }
            } else {
                throw new BadRequestException("Unknown action: " + action);
            }
        } catch (JsonException e) {
            throw new BadRequestException("Error updating DB credentials", e);
        }
        
    }
    
    /**
     * @return A connection from the pool. Call close on the connection when done to return to the pool.
     * @throws org.forgerock.openidm.objset.InternalServerErrorException
     */
    ODatabaseDocumentTx getConnection() throws InternalServerErrorException {
        ODatabaseDocumentTx db = null;
        int maxRetry = 100; // give it up to approx 10 seconds to recover
        int retryCount = 0;
        
        synchronized (dbLock) {
            while (db == null && retryCount < maxRetry) {
                retryCount++;
                try {
                    db = pool.acquire(dbURL, user, password);
                    if (retryCount > 1) {
                        logger.info("Succeeded in acquiring connection from pool in retry attempt {}", retryCount);
                    }
                    retryCount = maxRetry;
                } catch (com.orientechnologies.orient.core.exception.ORecordNotFoundException ex) {
                    // TODO: remove work-around once OrientDB resolves this condition
                    if (retryCount == maxRetry) {
                        logger.warn("Failure reported acquiring connection from pool, retried {} times before giving up.", retryCount, ex);
                        throw new InternalServerErrorException("Failure reported acquiring connection from pool, retried " + retryCount
                                        + " times before giving up: " + ex.getMessage(), ex);
                    } else {
                        logger.info("Pool acquire reported failure, retrying - attempt {}", retryCount);
                        logger.trace("Pool acquire failure detail ", ex);
                        try {
                            Thread.sleep(100); // Give the DB time to complete what it's doing before retrying
                        } catch (InterruptedException iex) {
                            // ignore that sleep was interrupted
                        }
                    }
                }
            }
        }
        return db;
    }

    // TODO: replace with common utility to handle ID, this is temporary
    private String getLocalId(String id) {
        String localId = null;
        int lastSlashPos = id.lastIndexOf("/");
        if (lastSlashPos > -1) {
            localId = id.substring(id.lastIndexOf("/") + 1);
        }
        logger.trace("Full id: {} Extracted local id: {}", id, localId);
        return localId;
    }
    
    // TODO: replace with common utility to handle ID, this is temporary
    private static String getObjectType(String id) {
        String type = null;
        int lastSlashPos = id.lastIndexOf("/");
        if (lastSlashPos > -1) {
            int startPos = 0;
            // This should not be necessary as relative URI should not start with slash
            if (id.startsWith("/")) {
                startPos = 1;
            }
            type = id.substring(startPos, lastSlashPos);
            logger.trace("Full id: {} Extracted type: {}", id, type);
        }
        return type;
    }
    
    public static String typeToOrientClassName(String type) {
        return type.replace("/", "_");
    }
    
    //public static String idToOrientClassName(String id) {
    //    String type = getObjectType(id);
    //    return typeToOrientClassName(type);
    //}

    /**
     * Detect if the root cause of the exception is a duplicate record.
     * This is necessary as the database may wrap this root cause in further exceptions,
     * masking the underlying cause
     * @param ex The throwable to check
     * @param maxLevels the maximum level of causes to check, avoiding the cost
     * of checking recursiveness
     * @return
     */
    private boolean isCauseRecordDuplicatedException(Throwable ex, int maxLevels) {
    	return isCauseException (ex, ORecordDuplicatedException.class, maxLevels);
    }
    
    /**
     * Detect if the root cause of the exception is an index constraint violation
     * This is necessary as the database may wrap this root cause in further exceptions,
     * masking the underlying cause
     * @param ex The throwable to check
     * @param maxLevels the maximum level of causes to check, avoiding the cost
     * of checking recursiveness
     * @return
     */
    private boolean isCauseIndexException(Throwable ex, int maxLevels) {
    	return isCauseException (ex, OIndexException.class, maxLevels);
    }
    
    /**
     * Detect if the root cause of the exception is an index constraint violation
     * This is necessary as the database may wrap this root cause in further exceptions,
     * masking the underlying cause
     * @param ex The throwable to check
     * @param maxLevels the maximum level of causes to check, avoiding the cost
     * of checking recursiveness
     * @return
     */
    private boolean isCauseConcurrentModificationException(Throwable ex, int maxLevels) {
    	return isCauseException (ex, OConcurrentModificationException.class, maxLevels);
    }
    
    /**
     * Detect if the root cause of the exception is a specific OrientDB exception
     * This is necessary as the database may wrap this root cause in further exceptions,
     * masking the underlying cause
     * @param ex The throwable to check
     * @param clazz the specific OrientDB exception to check for
     * @param maxLevels the maximum level of causes to check, avoiding the cost
     * of checking recursiveness
     * @return whether the root cause is the specified exception
     */
    private boolean isCauseException(Throwable ex, Class clazz, int maxLevels) {
        if (maxLevels > 0) {
            Throwable cause = ex.getCause();
            if (cause != null) {
                return clazz.isInstance(cause) || isCauseException(cause, clazz, maxLevels - 1);
            }
        }    
        return false;
    }

    /**
     * Populate and return a repository service that knows how to query and manipulate configuration.
     *
     * @param repoConfig the bootstrap configuration
     * @return the boot repository service. This instance is not managed by SCR and needs to be manually registered.
     */
    static OrientDBRepoService getRepoBootService(Map repoConfig) {
        if (bootRepo == null) {
            bootRepo = new OrientDBRepoService();
        }
        JsonValue cfg = new JsonValue(repoConfig);
        bootRepo.init(cfg);
        return bootRepo;
    }
    
    @Activate
    void activate(ComponentContext compContext) throws Exception {
        logger.debug("Activating Service with configuration {}", compContext.getProperties());
        
        try {
            existingConfig = enhancedConfig.getConfigurationAsJson(compContext);
        } catch (RuntimeException ex) {
            logger.warn("Configuration invalid and could not be parsed, can not start OrientDB repository: " 
                    + ex.getMessage(), ex);
            throw ex;
        }
        embeddedServer = new EmbeddedOServerService();
        embeddedServer.activate(existingConfig);
        init(existingConfig);
        
        logger.info("Repository started.");
    }
    
    /**
     * Initialize the instance with the given configuration.
     * 
     * This can configure managed (DS/SCR) instances, as well as explicitly instantiated
     * (bootstrap) instances.
     * 
     * @param config the configuration
     */
    void init (JsonValue config) {
        synchronized (dbLock) {
            try {
                dbURL = getDBUrl(config);
                logger.info("Use DB at dbURL: {}", dbURL);
                user = getUser(config);
                password = getPassword(config);
                poolMinSize = getPoolMinSize(config);
                poolMaxSize = getPoolMaxSize(config);

                Map map = config.get(CONFIG_QUERIES).asMap();
                Map<String, String> queryMap = (Map<String, String>) map;
                queries.setConfiguredQueries(queryMap);
            } catch (RuntimeException ex) {
                logger.warn("Configuration invalid, can not start OrientDB repository", ex);
                throw ex;
            }

            try {
                pool = DBHelper.getPool(dbURL, user, password, poolMinSize, poolMaxSize, config, true);
                logger.debug("Obtained pool {}", pool);
            } catch (RuntimeException ex) {
                logger.warn("Initializing database pool failed", ex);
                throw ex;
            }
        }
    }
    
    private String getDBUrl(JsonValue config) {
        File dbFolder = IdentityServer.getFileForWorkingPath("db/openidm");
        String orientDbFolder = dbFolder.getAbsolutePath();
        orientDbFolder = orientDbFolder.replace('\\', '/'); // OrientDB does not handle backslashes well
        return config.get(OrientDBRepoService.CONFIG_DB_URL).defaultTo("plocal:" + orientDbFolder).asString();
    }
    
    private String getUser(JsonValue config) {
        return config.get(CONFIG_USER).defaultTo("admin").asString();
    }
    
    private String getPassword(JsonValue config) {
        return config.get(CONFIG_PASSWORD).defaultTo("admin").asString();
    }
    
    private int getPoolMinSize(JsonValue config) {
        return config.get(CONFIG_POOL_MIN_SIZE).defaultTo(DEFAULT_POOL_MIN_SIZE).asInteger();
    }
    
    private int getPoolMaxSize(JsonValue config) {
        return config.get(CONFIG_POOL_MAX_SIZE).defaultTo(DEFAULT_POOL_MAX_SIZE).asInteger();
    }
    
    /**
     * Handle an existing activated service getting changed; 
     * e.g. configuration changes or dependency changes
     * 
     * @param compContext THe OSGI component context
     * @throws Exception if handling the modified event failed
     */
    @Modified
    void modified(ComponentContext compContext) throws Exception {
        logger.debug("Handle repository service modified notification");
        JsonValue newConfig = null;
        try {
            newConfig = enhancedConfig.getConfigurationAsJson(compContext);
        } catch (RuntimeException ex) {
            logger.warn("Configuration invalid and could not be parsed, can not start OrientDB repository", ex); 
            throw ex;
        }
        if (existingConfig != null 
                && dbURL.equals(getDBUrl(newConfig))
                && poolMinSize == getPoolMinSize(newConfig)
                && poolMaxSize == getPoolMaxSize(newConfig)) {
            // If the DB pool settings don't change keep the existing pool
            logger.info("(Re-)initialize repository with latest configuration.");
        } else {
            // If the DB pool settings changed do a more complete re-initialization
            logger.info("Re-initialize repository with latest configuration - including DB pool setting changes.");
            DBHelper.closePool(dbURL, pool);
        }
        init(newConfig);
        
        if (bootRepo != null) {
            bootRepo.init(newConfig);
        }
        
        existingConfig = newConfig;
        logger.debug("Repository service modified");
    }
    
    @Deactivate
    void deactivate(ComponentContext compContext) { 
        logger.debug("Deactivating Service {}", compContext);
        cleanup();
        if (embeddedServer != null) {
            embeddedServer.deactivate();
        }
        logger.info("Repository stopped.");
    }

    /**
     * Cleanup and close the repository
     */
    void cleanup() {
        DBHelper.closePools();
    }
}
