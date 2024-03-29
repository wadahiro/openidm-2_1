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
package org.forgerock.openidm.external.rest;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.ConfigurationPolicy;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Service;
import org.codehaus.jackson.map.ObjectMapper;
import org.forgerock.json.fluent.JsonValue;
import org.forgerock.openidm.config.EnhancedConfig;
import org.forgerock.openidm.config.JSONEnhancedConfig;
import org.forgerock.openidm.objset.BadRequestException;
import org.forgerock.openidm.objset.ConflictException;
import org.forgerock.openidm.objset.ForbiddenException;
import org.forgerock.openidm.objset.InternalServerErrorException;
import org.forgerock.openidm.objset.NotFoundException;
import org.forgerock.openidm.objset.ObjectSetException;
import org.forgerock.openidm.objset.ObjectSetExceptionFactory;
import org.forgerock.openidm.objset.ObjectSetJsonResource;
import org.forgerock.openidm.objset.Patch;
import org.forgerock.openidm.objset.PreconditionFailedException;
import org.osgi.service.component.ComponentContext;
import org.restlet.Client;
import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.CacheDirective;
import org.restlet.data.ChallengeRequest;
import org.restlet.data.ChallengeResponse;
import org.restlet.data.ChallengeScheme;
import org.restlet.data.CharacterSet;
import org.restlet.data.Cookie;
import org.restlet.data.Digest;
import org.restlet.data.Disposition;
import org.restlet.data.Encoding;
import org.restlet.data.Expectation;
import org.restlet.data.Form;
import org.restlet.data.Language;
import org.restlet.data.MediaType;
import org.restlet.data.Metadata;
import org.restlet.data.Parameter;
import org.restlet.data.Preference;
import org.restlet.data.Protocol;
import org.restlet.data.Range;
import org.restlet.data.Reference;
import org.restlet.data.Status;
import org.restlet.data.Tag;
import org.restlet.data.Warning;
import org.restlet.engine.http.header.CookieReader;
import org.restlet.engine.http.header.HeaderConstants;
import org.restlet.engine.util.Base64;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.resource.ClientResource;
import org.restlet.resource.ResourceException;
import org.restlet.util.Series;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * External REST connectivity
 *
 * @author aegloff
 */
@Component(name = RestService.PID, immediate = true, policy = ConfigurationPolicy.OPTIONAL, enabled = true)
@Service
@Properties({
        @Property(name = "service.description", value = "REST connectivity"),
        @Property(name = "service.vendor", value = "ForgeRock AS"),
        @Property(name = "openidm.router.prefix", value = "external/rest")
})
public class RestService extends ObjectSetJsonResource {
    final static Logger logger = LoggerFactory.getLogger(RestService.class);
    public static final String PID = "org.forgerock.openidm.external.rest";

    // Keys in the JSON configuration
    //public static final String CONFIG_X = "X";

    // Keys in the request parameters to override config
    public static final String ARG_URL = "_url";
    public static final String ARG_RESULT_FORMAT = "_result-format";
    public static final String ARG_BODY = "_body";
    public static final String ARG_CONTENT_TYPE = "_content-type";
    public static final String ARG_HEADERS = "_headers";
    public static final String ARG_AUTHENTICATE = "_authenticate";
    public static final String ARG_METHOD = "_method";

    EnhancedConfig enhancedConfig = new JSONEnhancedConfig();
    ObjectMapper mapper = new ObjectMapper();

    /**
     * Currently not supported by this implementation.
     * <p/>
     * Gets an object from the repository by identifier.
     *
     * @param fullId the identifier of the object to retrieve from the object set.
     * @return the requested object.
     * @throws NotFoundException   if the specified object could not be found.
     * @throws ForbiddenException  if access to the object is forbidden.
     * @throws BadRequestException if the passed identifier is invalid
     */
    @Override
    public Map<String, Object> read(String fullId) throws ObjectSetException {
        throw new UnsupportedOperationException();
    }

    /**
     * Currently not supported by this implementation.
     * <p/>
     * Creates a new object in the object set.
     *
     * @param fullId the client-generated identifier to use, or {@code null} if server-generated identifier is requested.
     * @param obj    the contents of the object to create in the object set.
     * @throws NotFoundException           if the specified id could not be resolved.
     * @throws ForbiddenException          if access to the object or object set is forbidden.
     * @throws PreconditionFailedException if an object with the same ID already exists.
     */
    @Override
    public void create(String fullId, Map<String, Object> obj) throws ObjectSetException {
        throw new UnsupportedOperationException();
    }

    /**
     * Currently not supported by this implementation.
     * <p/>
     * Updates the specified object in the object set.
     *
     * @param fullId the identifier of the object to be put, or {@code null} to request a generated identifier.
     * @param rev    the version of the object to update; or {@code null} if not provided.
     * @param obj    the contents of the object to put in the object set.
     * @throws ConflictException           if version is required but is {@code null}.
     * @throws ForbiddenException          if access to the object is forbidden.
     * @throws NotFoundException           if the specified object could not be found.
     * @throws PreconditionFailedException if version did not match the existing object in the set.
     * @throws BadRequestException         if the passed identifier is invalid
     */
    @Override
    public void update(String fullId, String rev, Map<String, Object> obj) throws ObjectSetException {
        throw new UnsupportedOperationException();
    }

    /**
     * Currently not supported by this implementation.
     * <p/>
     * Deletes the specified object from the object set.
     *
     * @param fullId the identifier of the object to be deleted.
     * @param rev    the version of the object to delete or {@code null} if not provided.
     * @throws NotFoundException           if the specified object could not be found.
     * @throws ForbiddenException          if access to the object is forbidden.
     * @throws ConflictException           if version is required but is {@code null}.
     * @throws PreconditionFailedException if version did not match the existing object in the set.
     */
    @Override
    public void delete(String fullId, String rev) throws ObjectSetException {
        throw new UnsupportedOperationException();
    }

    /**
     * Currently not supported by this implementation.
     * <p/>
     * Applies a patch (partial change) to the specified object in the object set.
     *
     * @param id    the identifier of the object to be patched.
     * @param rev   the version of the object to patch or {@code null} if not provided.
     * @param patch the partial change to apply to the object.
     * @throws ConflictException           if patch could not be applied object state or if version is required.
     * @throws ForbiddenException          if access to the object is forbidden.
     * @throws NotFoundException           if the specified object could not be found.
     * @throws PreconditionFailedException if version did not match the existing object in the set.
     */
    @Override
    public void patch(String id, String rev, Patch patch) throws ObjectSetException {
        throw new UnsupportedOperationException();
    }

    /**
     * Currently not supported by this implementation.
     * <p/>
     * Performs the query on the specified object and returns the associated results.
     *
     * @param fullId identifies the object to query.
     * @param params the parameters of the query to perform.
     * @return the query results, which includes meta-data and the result records in JSON object structure format.
     * @throws NotFoundException   if the specified object could not be found.
     * @throws BadRequestException if the specified params contain invalid arguments, e.g. a query id that is not
     *                             configured, a query expression that is invalid, or missing query substitution tokens.
     * @throws ForbiddenException  if access to the object or specified query is forbidden.
     */
    @Override
    public Map<String, Object> query(String fullId, Map<String, Object> params) throws ObjectSetException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, Object> action(String id, Map<String, Object> params) throws ObjectSetException {

        //TODO: This is work in progress, expect enhancements and changes.

        logger.debug("Action invoked on {} with {}", id, params);
        Map<String, Object> result = null;

        if (params == null) {
            throw new BadRequestException("Invalid action call on " + id + " : missing parameters to define what to invoke.");
        }

        // Handle Document coming from external, currently wrapped in an _entity object
        // TODO: Review inbound Restlet Mapping
        if (params.get("_entity") != null) {
            params = (Map<String, Object>) params.get("_entity");
        }

        String url = (String) params.get(ARG_URL);
        String method = (String) params.get(ARG_METHOD);
        Map<String, String> auth = (Map<String, String>) params.get(ARG_AUTHENTICATE);
        Map<String, String> headers = (Map<String, String>) params.get(ARG_HEADERS);
        String contentType = (String) params.get(ARG_CONTENT_TYPE);
        String body = (String) params.get(ARG_BODY);
        String resultFormat = (String) params.get(ARG_RESULT_FORMAT);
        //int timeout = params.get("_timeout");
        
        MediaType mediaType;
        if (contentType != null) {
            mediaType = new MediaType(contentType);
        } else {
            // Default
            mediaType = MediaType.APPLICATION_JSON;
        }
        
        // Whether the data type format to return to the caller should be inferred, or is explicitly defined
        boolean detectResultFormat = true;
        if (resultFormat != null && !resultFormat.equals("auto")) {
            detectResultFormat = false;
        }

        if (url == null) {
            throw new BadRequestException("Invalid action call on " + id + " : missing required argument " + ARG_URL);
        }

        try {
            ClientResource cr = new ClientResource(url);
            Map<String, Object> attrs = cr.getRequestAttributes();
            Request request = cr.getRequest();

            setAttributes(request, attrs, headers);

            if (auth != null) {
                String type = auth.get("type");
                if (type == null) {
                    type = "basic";
                }
                if ("basic".equalsIgnoreCase(type)) {
                    String identifier = auth.get("user");
                    String secret = auth.get("password");
                    logger.debug("Using basic authentication for {} secret supplied: {}", identifier, (secret != null));
                    ChallengeResponse challengeResponse = new ChallengeResponse(ChallengeScheme.HTTP_BASIC, identifier, secret);
                    cr.setChallengeResponse(challengeResponse);
                }
            }

            // Default method if none supplied
            if (method == null) {
                method = "post";
            }

            StringRepresentation rep = new StringRepresentation(body);
            rep.setMediaType(mediaType);
            
            Representation representation = null;
            try {
                if ("get".equalsIgnoreCase(method)) {
                    representation = cr.get();
                } else if ("post".equalsIgnoreCase(method)) {
                    representation = cr.post(rep);
                } else if ("put".equalsIgnoreCase(method)) {
                    representation = cr.put(rep);
                } else if ("delete".equalsIgnoreCase(method)) {
                    representation = cr.delete();
                } else if ("head".equalsIgnoreCase(method)) {
                    representation = cr.head();
                } else if ("options".equalsIgnoreCase(method)) {
                    // TODO: media type arg?
                    representation = cr.options();
                } else {
                    throw new BadRequestException("Unknown method " + method);
                }
            } catch (ResourceException e) {
                int code = e.getStatus().getCode();
                String text = null;
                Representation responseEntity = cr.getResponseEntity();
                if (responseEntity != null) {
                    text = cr.getResponseEntity().getText();
                }
                if (text != null) {
                    Map<String, Object> detail = new HashMap<String, Object>();
                    detail.put("_body", text);
                    throw ObjectSetExceptionFactory.getInstance(code, "Error while processing " 
                            + method + " request: " + e.getMessage(), detail, e);
                } else {
                    throw ObjectSetExceptionFactory.getInstance(code, "Error while processing "
                            + method + " request: " + e.getMessage(), e);
                }

            }

            Map<String, Object> responseAttributes = cr.getResponseAttributes();

            String text = representation.getText();
            logger.debug("Response: {} Response Attributes: {}", text, responseAttributes);

            if ((!detectResultFormat && resultFormat.equals(MediaType.APPLICATION_JSON))
                    || (detectResultFormat && representation.getMediaType().isCompatible(MediaType.APPLICATION_JSON))) {
                try {
                    if (text != null && text.trim().length() > 0) {
                        result = mapper.readValue(text, Map.class);
                    }
                } catch (Exception ex) {
                    throw new InternalServerErrorException("Failure in parsing the response as JSON: " + text
                            + " Reported failure: " + ex.getMessage(), ex);
                }
            } else {
                try {
                    Map<String, Object> resultHeaders = new HashMap<String, Object>();
                    Form respHeaders = (Form)responseAttributes.get("org.restlet.http.headers");
                    if (respHeaders != null) {
                        for (Parameter param : respHeaders) {
                            String name = param.getName();
                            String value = param.getValue();
                            resultHeaders.put(name, value);
                            logger.debug("Adding Response Attribute: {} : {}", name, value);
                        }
                    }
                    result = new HashMap<String, Object>();
                    result.put("_headers", resultHeaders);
                    result.put("_body", text);
                } catch (Exception ex) {
                    throw new InternalServerErrorException("Failure in parsing the response: " + text
                            + " Reported failure: " + ex.getMessage(), ex);
                }
            }
            cr.release();
        } catch (java.io.IOException ex) {
            throw new InternalServerErrorException("Failed to invoke " + params, ex);
        }

        logger.trace("Action result on {} : {}", id, result);

        return result;
    }


    @Activate
    void activate(ComponentContext compContext) throws Exception {
        logger.debug("Activating Service with configuration {}", compContext.getProperties());

        JsonValue config = null;
        try {
            config = enhancedConfig.getConfigurationAsJson(compContext);
        } catch (RuntimeException ex) {
            logger.warn("Configuration invalid and could not be parsed, can not start external REST connectivity: "
                    + ex.getMessage(), ex);
            throw ex;
        }

        init(config);

        logger.info("External REST connectivity started.");
    }

    /**
     * Initialize the instance with the given configuration.
     *
     * @param config the configuration
     */
    void init(JsonValue config) {
    }

    /* Currently rely on deactivate/activate to be called by DS if config changes instead
    @Modified
    void modified(ComponentContext compContext) {
    }
    */


    @Deactivate
    void deactivate(ComponentContext compContext) {
        logger.debug("Deactivating Service {}", compContext);

        logger.info("External REST connectivity stopped.");
    }

    public static ClientResource createClientResource(JsonValue params) {
        //TODO use the https://wikis.forgerock.org/confluence/display/json/http-request
        String url = params.get(ARG_URL).required().asString();
        Context context = new Context();

        context.getParameters().set("maxTotalConnections", "16");
        context.getParameters().set("maxConnectionsPerHost", "8");

        ClientResource cr = new ClientResource(context, url);
        JsonValue _authenticate = params.get(ARG_AUTHENTICATE);

        if (!_authenticate.isNull()) {
            ChallengeScheme authType = ChallengeScheme.valueOf(_authenticate.get("type").asString());
            if (authType == null) {
                authType = ChallengeScheme.HTTP_BASIC;
            }
            if (ChallengeScheme.HTTP_BASIC.equals(authType)) {
                String identifier = _authenticate.get("user").required().asString();
                String secret = _authenticate.get("password").asString();
                logger.debug("Using basic authentication for {} secret supplied: {}", identifier, (secret != null));
                ChallengeResponse challengeResponse = new ChallengeResponse(authType, identifier, secret);
                cr.setChallengeResponse(challengeResponse);
                cr.getRequest().setChallengeResponse(challengeResponse);
            }
            if (ChallengeScheme.HTTP_COOKIE.equals(authType)) {

                String authenticationTokenPath = "openidm/j_security_check";

                // Prepare the request
                Request request = new Request(org.restlet.data.Method.POST, authenticationTokenPath
                        + authenticationTokenPath);

                Form loginForm = new Form();
                loginForm.add("j_username", "admin");
                loginForm.add("j_password", "admin");
                Representation repEnt = loginForm.getWebRepresentation();

                request.setEntity(repEnt);

                Client client = new Client(Protocol.HTTP);

                request.setEntity(repEnt);
                Response res = client.handle(request);

                String identifier = _authenticate.get("user").required().asString();
                String secret = _authenticate.get("password").asString();
                logger.debug("Using cookie authentication for {} secret supplied: {}", identifier, (secret != null));
                ChallengeResponse challengeResponse = new ChallengeResponse(authType, identifier, secret);
                cr.setChallengeResponse(challengeResponse);
                cr.getRequest().setChallengeResponse(challengeResponse);
            }
        }

        return cr;
    }

    private void setAttributes(Request request, Map<String, Object> attributes, Map<String, String> headers) {
        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz");
        
        if (headers != null) {
            org.restlet.data.Form extraHeaders = (org.restlet.data.Form) attributes.get("org.restlet.http.headers");
            if (extraHeaders == null) {
                extraHeaders = new org.restlet.data.Form();
                attributes.put("org.restlet.http.headers", extraHeaders);
            }
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String httpHeader = entry.getKey();
                logger.info("Adding header {}: {}", entry.getKey(), entry.getValue());
                if (httpHeader.equals(HeaderConstants.HEADER_ACCEPT)) {
                    List<Preference<MediaType>> mediaTypes = request.getClientInfo().getAcceptedMediaTypes();
                    String [] types = entry.getValue().split(",");
                    for (String type : types) {
                        String [] parts = type.split(";");
                        String name = parts[0];
                        MediaType mediaType = MediaType.valueOf(name);
                        Preference pref = new Preference(mediaType);
                        addPreferences(pref, parts);
                        mediaTypes.add(pref);
                    }
                    //attributes.put("request.clientInfo.acceptedMediaTypes", new Preference(new MediaType(entry.getValue())));
                } else if (httpHeader.equals(HeaderConstants.HEADER_ACCEPT_CHARSET)) {
                    List<Preference<CharacterSet>> characterSets = request.getClientInfo().getAcceptedCharacterSets();
                    String [] sets = entry.getValue().split(",");
                    for (String set : sets) {
                        String [] parts = set.split(";");
                        String name = parts[0];
                        CharacterSet characterSet = CharacterSet.valueOf(name);
                        Preference pref = new Preference(characterSet);
                        addPreferences(pref, parts);
                        characterSets.add(pref);
                    }
                    //attributes.put("request.clientInfo.acceptedCharacterSets", new Preference(new CharacterSet(entry.getValue())));
                } else if (httpHeader.equals(HeaderConstants.HEADER_ACCEPT_ENCODING)) {
                    List<Preference<Encoding>> encodingsList = request.getClientInfo().getAcceptedEncodings();
                    String [] encodings = entry.getValue().split(",");
                    for (String enc : encodings) {
                        String [] parts = enc.split(";");
                        String name = parts[0];
                        Encoding encoding = Encoding.valueOf(name);
                        Preference pref = new Preference(encoding);
                        addPreferences(pref, parts);
                        encodingsList.add(pref);
                    }
                    //attributes.put("request.clientInfo.acceptedEncodings", new Preference(new Encoding(entry.getValue())));
                } else if (httpHeader.equals(HeaderConstants.HEADER_ACCEPT_LANGUAGE)) {
                    List<Preference<Language>> languagesList = request.getClientInfo().getAcceptedLanguages();
                    String [] languages = entry.getValue().split(",");
                    for (String lang : languages) {
                        String [] parts = lang.split(";");
                        String name = parts[0];
                        Language language = Language.valueOf(name);
                        Preference pref = new Preference(language);
                        addPreferences(pref, parts);
                        languagesList.add(pref);
                    }
                    //attributes.put("request.clientInfo.acceptedLanguages", new Preference(new Language(entry.getValue())));
                } else if (httpHeader.equals(HeaderConstants.HEADER_ACCEPT_RANGES)) {
                    attributes.put("response.serverInfo.acceptRanges", Boolean.parseBoolean(entry.getValue()));
                } else if (httpHeader.equals(HeaderConstants.HEADER_AGE)) {
                    attributes.put("response.age", Integer.parseInt(entry.getValue()));
                } else if (httpHeader.equals(HeaderConstants.HEADER_AUTHORIZATION)) {
                    attributes.put("request.challengeResponse", new ChallengeResponse(ChallengeScheme.valueOf(entry.getValue())));
                } else if (httpHeader.equals(HeaderConstants.HEADER_CACHE_CONTROL)) {
                    List<CacheDirective> cacheDirectives = new ArrayList<CacheDirective>();
                    String [] cacheControls = entry.getValue().split(",");
                    for (String str : cacheControls) {
                        String name = null, value = null;
                        int i = str.indexOf("=");
                        if (i > -1) {
                            name = str.substring(0, i).trim();
                            value = str.substring(i + 1).trim();
                        } else {
                            name = str.trim();
                        }
                        if (name.equals(HeaderConstants.CACHE_MAX_AGE)) {
                            cacheDirectives.add(CacheDirective.maxAge(Integer.parseInt(value)));
                        } else if (name.equals(HeaderConstants.CACHE_MAX_STALE)) {
                            if (value != null) {
                                cacheDirectives.add(CacheDirective.maxStale(Integer.parseInt(value)));
                            } else {
                                cacheDirectives.add(CacheDirective.maxStale());
                            }
                        } else if (name.equals(HeaderConstants.CACHE_MIN_FRESH)) {
                            cacheDirectives.add(CacheDirective.minFresh(Integer.parseInt(value)));
                        } else if (name.equals(HeaderConstants.CACHE_MUST_REVALIDATE)) {
                            cacheDirectives.add(CacheDirective.mustRevalidate());
                        } else if (name.equals(HeaderConstants.CACHE_NO_CACHE)) {
                            if (value != null) {
                                cacheDirectives.add(CacheDirective.noCache(value));
                            } else {
                                cacheDirectives.add(CacheDirective.noCache());
                            }
                        } else if (name.equals(HeaderConstants.CACHE_NO_STORE)) {
                            cacheDirectives.add(CacheDirective.noStore());
                        } else if (name.equals(HeaderConstants.CACHE_NO_TRANSFORM)) {
                            cacheDirectives.add(CacheDirective.noTransform());
                        } else if (name.equals(HeaderConstants.CACHE_ONLY_IF_CACHED)) {
                            cacheDirectives.add(CacheDirective.onlyIfCached());
                        } else if (name.equals(HeaderConstants.CACHE_PRIVATE)) {
                            if (value != null) {
                                cacheDirectives.add(CacheDirective.privateInfo(value));
                            } else {
                                cacheDirectives.add(CacheDirective.privateInfo());
                            }
                        } else if (name.equals(HeaderConstants.CACHE_PROXY_MUST_REVALIDATE)) {
                            cacheDirectives.add(CacheDirective.proxyMustRevalidate());
                        } else if (name.equals(HeaderConstants.CACHE_PUBLIC)) {
                            cacheDirectives.add(CacheDirective.publicInfo());
                        } else if (name.equals(HeaderConstants.CACHE_SHARED_MAX_AGE)) {
                            cacheDirectives.add(CacheDirective.sharedMaxAge(Integer.parseInt(value)));
                        } else {
                            logger.info("Unknown HTTP header Cache-Control entry: {}", str);
                        }
                    }
                    attributes.put("message.cacheDirectives", cacheDirectives);
                } else if (httpHeader.equals(HeaderConstants.HEADER_CONNECTION)) {
                    // [HTTP Connectors]
                } else if (httpHeader.equals(HeaderConstants.HEADER_CONTENT_DISPOSITION)) {
                    attributes.put("message.entity.disposition", new Disposition(entry.getValue()));
                } else if (httpHeader.equals(HeaderConstants.HEADER_CONTENT_ENCODING)) {
                    List<Encoding> contentEncodings = new ArrayList<Encoding>();
                    String [] encodings = entry.getValue().split(",");
                    for (String encoding : encodings) {
                        contentEncodings.add(Encoding.valueOf(encoding.trim()));
                    }
                    attributes.put("message.entity.encodings", contentEncodings);
                } else if (httpHeader.equals(HeaderConstants.HEADER_CONTENT_LANGUAGE)) {
                    List<Language> contentLanguages = new ArrayList<Language>();
                    String [] languages = entry.getValue().split(",");
                    for (String language : languages) {
                        contentLanguages.add(Language.valueOf(language.trim()));
                    }
                    attributes.put("message.entity.languages", contentLanguages);
                } else if (httpHeader.equals(HeaderConstants.HEADER_CONTENT_LENGTH)) {
                    attributes.put("message.entity.size", Long.parseLong(entry.getValue()));
                } else if (httpHeader.equals(HeaderConstants.HEADER_CONTENT_LOCATION)) {
                    try {
                        Reference ref = new Reference(new URI(entry.getValue()));
                        attributes.put("message.entity.locationRef", ref);
                    } catch (URISyntaxException e) {
                        logger.info("Problem parsing HTTP Content-Location header", e);
                    }
                } else if (httpHeader.equals(HeaderConstants.HEADER_CONTENT_MD5)) {
                    attributes.put("message.entity.digest", new Digest(Base64.decode(entry.getValue())));
                } else if (httpHeader.equals(HeaderConstants.HEADER_CONTENT_RANGE)) {
                    String rangeString = entry.getValue().split(" ")[1];
                    rangeString = rangeString.substring(rangeString.indexOf("/"));
                    Range range;
                    if (rangeString.equals("*")) {
                        range = new Range();
                    } else {
                        long index = Long.parseLong(rangeString.substring(0, rangeString.indexOf("-")));
                        long size = Long.parseLong(rangeString.substring(rangeString.indexOf("-") + 1)) - index + 1;
                        range = new Range(size, index);
                    }
                    attributes.put("message.entity.range", range);
                } else if (httpHeader.equals(HeaderConstants.HEADER_CONTENT_TYPE)) {
                    attributes.put("message.entity.mediaType", new MediaType(entry.getValue()));
                } else if (httpHeader.equals(HeaderConstants.HEADER_COOKIE)) {
                    CookieReader cr = new CookieReader(entry.getValue());
                    List<Cookie> cookies = cr.readValues();
                    Series<Cookie> restletCookies = request.getCookies();
                    for (Cookie cookie : cookies) {
                        restletCookies.add(cookie);
                    }
                } else if (httpHeader.equals(HeaderConstants.HEADER_DATE)) {
                    try {
                        Date d = format.parse(entry.getValue());
                        attributes.put("message.date", d);
                    } catch (ParseException e) {
                        logger.error("Error parsing HTTP Date header", e);
                    }
                } else if (httpHeader.equals(HeaderConstants.HEADER_ETAG)) {
                    attributes.put("message.entity.tag", Tag.parse(entry.getValue()));
                } else if (httpHeader.equals(HeaderConstants.HEADER_EXPECT)) {
                    if (entry.getValue().equals("100-continue")) {
                        request.getClientInfo().getExpectations().add(Expectation.continueResponse());
                    }
                } else if (httpHeader.equals(HeaderConstants.HEADER_EXPIRES)) {
                    try {
                        Date d = format.parse(entry.getValue());
                        attributes.put("message.entity.expirationDate", d);
                    } catch (ParseException e) {
                        logger.error("Error parsing HTTP Expires header", e);
                    }
                } else if (httpHeader.equals(HeaderConstants.HEADER_FROM)) {
                    attributes.put("request.clientInfo.from", entry.getValue());
                } else if (httpHeader.equals(HeaderConstants.HEADER_HOST)) {
                    try {
                        Reference ref = new Reference(new URI(entry.getValue()));
                        attributes.put("request.hostRef", ref);
                    } catch (URISyntaxException e) {
                        logger.error("Error parsing HTTP Host header", e);
                    }
                } else if (httpHeader.equals(HeaderConstants.HEADER_IF_MATCH)) {
                    String [] tags = entry.getValue().split(",");
                    List<Tag> list = request.getConditions().getMatch();
                    for (String tag : tags) {
                        list.add(Tag.parse(tag));
                    }
                } else if (httpHeader.equals(HeaderConstants.HEADER_IF_MODIFIED_SINCE)) {
                    try {
                        request.getConditions().setModifiedSince(format.parse(entry.getValue()));
                    } catch (ParseException e) {
                        logger.error("Error parsing HTTP Modified-Since header", e);
                    }
                } else if (httpHeader.equals(HeaderConstants.HEADER_IF_NONE_MATCH)) {
                    String [] tags = entry.getValue().split(",");
                    List<Tag> list = request.getConditions().getNoneMatch();
                    for (String tag : tags) {
                        list.add(Tag.parse(tag));
                    }
                } else if (httpHeader.equals(HeaderConstants.HEADER_IF_RANGE)) {
                    Date rangeDate = null;
                    Tag rangeTag = null;
                    try {
                        rangeDate = format.parse(entry.getValue());
                        request.getConditions().setRangeDate(rangeDate);
                    } catch (ParseException e) {
                        rangeTag = Tag.parse(entry.getValue());
                        request.getConditions().setRangeTag(rangeTag);
                    }
                } else if (httpHeader.equals(HeaderConstants.HEADER_IF_UNMODIFIED_SINCE)) {
                    try {
                        request.getConditions().setUnmodifiedSince(format.parse(entry.getValue()));
                    } catch (ParseException e) {
                        logger.error("Error parsing HTTP If-Unmodified-Since header", e);
                    }
                } else if (httpHeader.equals(HeaderConstants.HEADER_LAST_MODIFIED)) {
                    try {
                        attributes.put("message.entity.modificationDate", format.parse(entry.getValue()));
                    } catch (ParseException e) {
                        logger.error("Error parsing HTTP Last-Modified header", e);
                    }
                } else if (httpHeader.equals(HeaderConstants.HEADER_MAX_FORWARDS)) {
                    request.setMaxForwards(Integer.parseInt(entry.getValue()));
                } else if (httpHeader.equals(HeaderConstants.HEADER_PROXY_AUTHORIZATION)) {
                    request.setProxyChallengeResponse(new ChallengeResponse(ChallengeScheme.valueOf(entry.getValue())));
                } else if (httpHeader.equals(HeaderConstants.HEADER_RANGE)) {
                    String rangeSection = entry.getValue().split("=")[1];
                    String [] ranges = rangeSection.split(",");
                    List<Range> rangeList = new ArrayList<Range>();
                    for (String range : ranges) {
                        Range r;
                        if (range.startsWith("-")) { 
                            r = new Range(-1, Integer.parseInt(range.substring(1)));
                        } else if(range.indexOf("-") == -1) {
                            r = new Range(Integer.parseInt(range));
                        } else if (range.endsWith("-")) {
                            r = new Range(-1, Integer.parseInt(range.substring(0, range.length()-1)));
                        } else {
                            long index = Long.parseLong(range.substring(0, range.indexOf("-")));
                            long size = Long.parseLong(range.substring(range.indexOf("-") + 1)) - index + 1;
                            r = new Range(size, index);
                        }
                        rangeList.add(r);
                    }
                    request.setRanges(rangeList);
                } else if (httpHeader.equals(HeaderConstants.HEADER_REFERRER)) {
                    try {
                        Reference ref = new Reference(new URI(entry.getValue()));
                        attributes.put("request.refererRef", ref);
                    } catch (URISyntaxException e) {
                        logger.error("Error parsing HTTP Referrer header", e);
                    }
                } else if (httpHeader.equals(HeaderConstants.HEADER_TRANSFER_ENCODING)) {
                    // [HTTP Connectors]
                } else if (httpHeader.equals(HeaderConstants.HEADER_USER_AGENT)) {
                    attributes.put("request.clientInfo.agent", entry.getValue());
                } else if (httpHeader.equals(HeaderConstants.HEADER_VARY)) {
                    attributes.put("response.dimensions", entry.getValue());
                } else if (httpHeader.equals(HeaderConstants.HEADER_VIA)) {
                    //return "message.recipientsInfo";
                } else if (httpHeader.equals(HeaderConstants.HEADER_WARNING)) {
                    try {
                        List<Warning> warnings = (List<Warning>) attributes.get("message.warnings");
                        if (warnings == null) {
                            warnings = new ArrayList<Warning>();
                            attributes.put("message.warnings", warnings);
                        }
                        Warning warning = new Warning();
                        String [] strs = entry.getValue().split(" ");
                        warning.setStatus(Status.valueOf(Integer.parseInt(strs[0])));
                        warning.setAgent(strs[1]);
                        warning.setText(strs[2]);
                        if (strs.length > 3) {
                            Date d = format.parse(strs[3]);
                            warning.setDate(d);
                        }
                        warnings.add(warning);
                    } catch (Exception e) {
                        logger.error("Error parsing HTTP Warning header", e);
                    }
                } else if (httpHeader.equals(HeaderConstants.HEADER_WWW_AUTHENTICATE)) {
                    attributes.put("response.challengeRequests", new ChallengeRequest(ChallengeScheme.valueOf(entry.getValue())));
                } else if (httpHeader.equals(HeaderConstants.HEADER_X_FORWARDED_FOR)) {
                    List<String> list = (List<String>) attributes.get("request.clientInfo.addresses");
                    if (list == null) {
                        list = new ArrayList<String>();
                        attributes.put("request.clientInfo.addresses", list);
                    }
                    list.add(entry.getValue());
                } else {
                    // Unsupported Header
                    logger.debug("Unsupported header");
                    extraHeaders.add(entry.getKey(), entry.getValue());
                } 
            }
        }
    }

    private <T extends Metadata> void addPreferences(Preference<T> pref, String [] parts) {
        if (parts.length > 1) {
            for (int i = 1; i < parts.length; i++) {
                String [] strs = parts[i].split("=");
                String n = strs[0].trim();
                String v = strs[1].trim();
                if (n.endsWith("q")) {
                    pref.setQuality(Float.parseFloat(v));
                } else {
                    pref.getParameters().add(n, v);
                }
            }
        }
    }
}