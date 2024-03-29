/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2011-2013 ForgeRock AS. All rights reserved.
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
package org.forgerock.openidm.managed;

// Java SE
import java.util.Map;

import org.forgerock.json.crypto.JsonCrypto;
import org.forgerock.json.crypto.JsonCryptoException;
import org.forgerock.json.crypto.JsonEncryptor;
import org.forgerock.json.fluent.JsonException;
import org.forgerock.json.fluent.JsonValue;
import org.forgerock.json.fluent.JsonValueException;
import org.forgerock.openidm.crypto.CryptoService;
import org.forgerock.openidm.objset.ForbiddenException;
import org.forgerock.openidm.objset.InternalServerErrorException;
import org.forgerock.openidm.script.Script;
import org.forgerock.openidm.script.ScriptException;
import org.forgerock.openidm.script.ScriptThrownException;
import org.forgerock.openidm.script.Scripts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A property defined within an managed object. Provides for the specification of triggers
 * to execute during the lifecycle of a managed object.
 *
 * @author Paul C. Bryan
 * @author ckienle
 */
class ManagedObjectProperty {

    private static enum Scope {
        PUBLIC, PRIVATE
    }

    /** TODO: Description. */
    private final static Logger LOGGER = LoggerFactory.getLogger(ManagedObjectProperty.class);

    /** TODO: Description. */
    private ManagedObjectService service;

    /** The name of the property within the managed object. */
    private String name;

    /** Script to execute when a property requires validation. */
    private Script onValidate;

    /** Script to execute once an property is retrieved from the repository. */
    private Script onRetrieve;

    /** Script to execute when an property is about to be stored in the repository. */
    private Script onStore;

    /** TODO: Description. */
    private JsonEncryptor encryptor;

    /** String that indicates the privacy level of the property. */
    private Scope scope;
    
    /** The CryptoService implementation */
    private CryptoService cryptoService;
    
    /** The encryption configuration */
    private JsonValue encryptionValue;

    /**
     * Constructs a new managed object property.
     *
     * @param service
     * @param config configuration object to use to initialize managed object property.
     * @throws JsonValueException if the configuration is malformed.
     */
    public ManagedObjectProperty(ManagedObjectService service, JsonValue config) throws JsonValueException {
        this.service = service;
        name = config.get("name").required().asString();
        onRetrieve = Scripts.newInstance("ManagedObjectProperty", config.get("onRetrieve"));
        onStore = Scripts.newInstance("ManagedObjectProperty", config.get("onStore"));
        onValidate = Scripts.newInstance("ManagedObjectProperty", config.get("onValidate"));
        encryptionValue = config.get("encryption");
        if (!encryptionValue.isNull()) {
            cryptoService = service.getCryptoService();
            setEncryptor();
        }

        scope = config.get("scope").asEnum(Scope.class);
        if (scope == null) {
            scope = Scope.PUBLIC;
        }
    }

    /**
     * A synchronized method for setting the excryptor is if hasn't already been set and 
     * there exists an encryption configuration.
     */
    private synchronized void setEncryptor() {
        if (encryptor == null && !encryptionValue.isNull()) {
            try {
                encryptor = service.getCryptoService().getEncryptor(
                        encryptionValue.get("cipher").defaultTo("AES/CBC/PKCS5Padding").asString(),
                        encryptionValue.get("key").required().asString());
            } catch (JsonCryptoException jce) {
                LOGGER.warn("Unable to set encryptor");
            }
        }
    }

    /**
     * Executes a script that performs a transformation of a property. Populates the
     * {@code "property"} property in the script scope with the property value. Any changes
     * to the property are reflected back into the managed object if the script successfully
     * completes.
     *
     * @param type type of script to execute.
     * @param script the script to execute, or {@code null} to execute nothing.
     * @param managedObject the managed object containing the property value.
     * @throws InternalServerErrorException if script execution fails.
     */
    private void execScript(String type, Script script, JsonValue managedObject) throws InternalServerErrorException {
        if (script != null) {
            Map<String, Object> scope = service.newScope();
            scope.put("property", managedObject.get(name).getObject());
            try {
                script.exec(scope);
            } catch (ScriptException se) {
                String msg = name + " " + type + " script encountered exception";
                LOGGER.debug(msg, se);
                throw new InternalServerErrorException(msg, se);
            }
            if (scope.containsKey("property")) { // property (still) defined in scope
                managedObject.put(name, scope.get("property")); // propagate it back to managed object
            } else if (managedObject.isDefined(name)) { // not in scope but was in managed object
                managedObject.remove(name); // remove it from managed object
            }
        }
    }

    /**
     * Executes the script if it exists, to validate a property value.
     *
     * @param value the JSON value containing the property value to be validated.
     * @throws ForbiddenException if validation of the property fails.
     * @throws InternalServerErrorException if any other exception occurs during execution.
     */
    void onValidate(JsonValue value) throws ForbiddenException, InternalServerErrorException {
        if (onValidate != null) {
            Map<String, Object> scope = service.newScope();
            scope.put("property", value.get(name).getObject());
            try {
                onValidate.exec(scope);
            } catch (ScriptThrownException ste) {
                throw new ForbiddenException(ste.getValue().toString()); // validation failed
            } catch (ScriptException se) {
                String msg = name + " onValidate script encountered exception";
                LOGGER.debug(msg, se);
                throw new InternalServerErrorException(msg, se);
            }
        }
    }

    /**
     * Performs tasks when a property has been retrieved from the repository, including:
     * executing the {@code onRetrieve} script.
     *
     * @param value the JSON value that was retrieved from the repository.
     * @throws InternalServerErrorException if an exception occurs processing the property.
     */
    void onRetrieve(JsonValue value) throws InternalServerErrorException {
        execScript("onRetrieve", onRetrieve, value);
    }

    /**
     * Performs tasks when a property is to be stored in the repository, including:
     * executing the {@code onStore} script and encrypting the property.
     *
     * @param value the JSON value to be stored in the repository.
     * @throws InternalServerErrorException if an exception occurs processing the property.
     */
    void onStore(JsonValue value) throws InternalServerErrorException {
        execScript("onStore", onStore, value);
        setEncryptor();
        if (encryptor != null && value.isDefined(name)) {
            if (service.getCryptoService() == null) {
                String msg = name + "property encryption service not available";
                LOGGER.debug(msg);
                throw new InternalServerErrorException(msg);
            }
            if (!service.getCryptoService().isEncrypted(value)) {
                try {
                    value.put(name, new JsonCrypto(encryptor.getType(),
                        encryptor.encrypt(value.get(name))).toJsonValue().getObject());
                } catch (JsonCryptoException jce) {
                    String msg = name + " property encryption exception";
                    LOGGER.debug(msg, jce);
                    throw new InternalServerErrorException(msg, jce);
                } catch (JsonException je) {
                    String msg = name + " property transformation exception";
                    LOGGER.debug(msg, je);
                    throw new InternalServerErrorException(msg, je);
                }
            }
        }
    }

    /**
     * Returns the name of the property.
     * @return
     */
    String getName() {
        return name;
    }

    public boolean isPrivate() {
        return Scope.PRIVATE.equals(scope);
    }

    public boolean isPublic() {
        return Scope.PUBLIC.equals(scope);
    }
}
