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
package org.forgerock.openidm.sync.impl;

// Java Standard Edition
import java.util.HashMap;
import java.util.Map;

import org.forgerock.json.fluent.JsonValue;
import org.forgerock.json.fluent.JsonValueException;
import org.forgerock.openidm.core.ServerConstants;
import org.forgerock.openidm.script.RegisteredScript;
import org.forgerock.openidm.script.Script;
import org.forgerock.openidm.script.ScriptException;
import org.forgerock.openidm.script.Scripts;
import org.forgerock.openidm.script.Utils;
import org.forgerock.openidm.sync.SynchronizationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TODO: Description.
 *
 * @author Paul C. Bryan
 * @author ckienle
 */
class Policy {

    /** TODO: Description. */
    private final static Logger LOGGER = LoggerFactory.getLogger(Policy.class);

    /** TODO: Description. */
    private final SynchronizationService service;

    /** TODO: Description. */
    private final Situation situation;

    /** TODO: Description. */
    private final Action action;

    /** TODO: Description. */
    private final Script script;

    private final Map<String,Object> scriptScope;

    /** TODO: Description. */
    private RegisteredScript postAction;

    /**
     * TODO: Description.
     *
     * @param service
     * @param config TODO.
     * @throws JsonValueException TODO.
     */
    public Policy(SynchronizationService service, JsonValue config) throws JsonValueException {
        this.service = service;
        situation = config.get("situation").required().asEnum(Situation.class);
        JsonValue action = config.get("action").required();
        if (action.isString()) {
            this.action = action.asEnum(Action.class);
            this.script = null;
            this.scriptScope = null;
        } else {
            this.action = null;
            this.script = Scripts.newInstance("Policy", action);
            if (action.isMap() && action.asMap().size() > 2) {
                // If there is additional attributes then copy them
                scriptScope = action.copy().asMap();
                scriptScope.remove("type");
                scriptScope.remove("source");
                scriptScope.remove("file");
            } else {
                scriptScope = null;
            }
        }
        JsonValue pAction = config.get("postAction");
        if (pAction.isNull()) {
            this.postAction = null;
        } else {
            this.postAction = new RegisteredScript(Scripts.newInstance("PostAction", pAction), pAction);
        }
    }

    /**
     * TODO: Description.
     * @return
     */
    public Situation getSituation() {
        return situation;
    }

    /**
     * TODO: Description.
     *
     * @param source
     * @param target
     * @param syncOperation the parent {@link ObjectMapping.SyncOperation} instance
     * @return TODO.
     * @throws SynchronizationException TODO.
     */
    public Action getAction(LazyObjectAccessor source, LazyObjectAccessor target, final ObjectMapping.SyncOperation syncOperation) throws SynchronizationException {
        Action result = null;
        if (action != null) { // static action specified
            result = action;
        } else if (script != null) { // action is dynamically determine 
            Map<String, Object> scope = service.newScope();
            if (null != scriptScope) {
                //Make a thread safe copy and put the variables into the scope
                for (Map.Entry<String,Object> entry: Utils.deepCopy(scriptScope).entrySet()){
                    if (scope.containsKey(entry.getKey())){
                        continue;
                    }
                    scope.put(entry.getKey(),entry.getValue());
                }
            }
            Map<String, Object> recon = new HashMap<String, Object>();
            scope.put("recon",recon);
            JsonValue actionParam  = null;
            if (syncOperation instanceof ObjectMapping.TargetSyncOperation) {
                actionParam = ((ObjectMapping.TargetSyncOperation) syncOperation).toJsonValue();
            } else if (syncOperation instanceof ObjectMapping.SourceSyncOperation) {
                actionParam = ((ObjectMapping.SourceSyncOperation)syncOperation).toJsonValue();
            }
            if (null != actionParam){
                actionParam.put(ServerConstants.ACTION_NAME,"performAction");
                recon.put("actionParam",actionParam.getObject());
            }

            scope.put("sourceAction", (syncOperation instanceof ObjectMapping.SourceSyncOperation));
            if (source != null) {
                scope.put("source", source.asMap());
            }
            if (target != null) {
                scope.put("target", target.asMap());
            }
            try {
                result = Enum.valueOf(Action.class, script.exec(scope).toString());
            } catch (NullPointerException npe) {
                throw new SynchronizationException("action script returned null value");
            } catch (IllegalArgumentException iae) {
                throw new SynchronizationException("action script returned invalid action");
            } catch (ScriptException se) {
                LOGGER.debug("action script encountered exception", se);
                throw new SynchronizationException(se);
            }
        }
        return result;
    }

    public void evaluatePostAction(LazyObjectAccessor source, LazyObjectAccessor target, Action action, boolean sourceAction) throws SynchronizationException {
        if (postAction != null) {
            Map<String, Object> scope = service.newScope();
            scope.put("sourceAction", sourceAction);
            scope.put("action", action.name());
            scope.put("situation", situation.name());
            if (source != null) {
                scope.put("source", source.asMap());
            }
            if (target != null) {
                scope.put("target", target.asMap());
            }
            JsonValue params = postAction.getParameters();
            scope.putAll(params.asMap());
            try {
                postAction.getScript().exec(scope);
            } catch (ScriptException se) {
                LOGGER.debug("action script encountered exception", se);
                throw new SynchronizationException(se);
            }
        }
    }
}
