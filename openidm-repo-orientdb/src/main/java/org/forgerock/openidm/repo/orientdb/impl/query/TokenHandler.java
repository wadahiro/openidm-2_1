/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright © 2011 ForgeRock AS. All rights reserved.
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
package org.forgerock.openidm.repo.orientdb.impl.query;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

//import org.forgerock.openidm.objset.ObjectSetException;
import org.forgerock.openidm.objset.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// TODO: replace use of this class with TokenHandler in org.forgerock.openidm.repo.util
public class TokenHandler {

    public static final String PREFIX_UNQUOTED = "unquoted";

    public static final String PREFIX_DOTNOTATION = "dotnotation";

    public static final String PREFIX_LIST = "list";

    final static Logger logger = LoggerFactory.getLogger(TokenHandler.class);
    
    // The OpenIDM query token is of format ${token-name}
    Pattern tokenPattern = Pattern.compile("\\$\\{(.+?)\\}");

    /**
     * Replaces a query string with tokens of format ${token-name} with the values from the
     * passed in map, where the token-name must be the key in the map
     * 
     * @param queryString the query with tokens
     * @param params the parameters to replace the tokens. Values can be String or List.
     * @return the query with all tokens replace with their found values
     * @throws BadRequestException if token in the query is not in the passed parameters
     */
    String replaceTokensWithValues(String queryString, Map<String, Object> params) 
            throws BadRequestException {
        java.util.regex.Matcher matcher = tokenPattern.matcher(queryString);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String fullTokenKey = matcher.group(1);
            String tokenKey = fullTokenKey;
            String tokenPrefix = null;
            String[] tokenKeyParts = tokenKey.split(":", 2);
            // if prefix found
            if (tokenKeyParts.length == 2) {
                tokenPrefix = tokenKeyParts[0];
                tokenKey = tokenKeyParts[1];
            }
            if (!params.containsKey(tokenKey)) {
                // fail with an exception if token not found
                throw new BadRequestException("Missing entry in params passed to query for token " + tokenKey);
            } else {
                Object replacement = params.get(tokenKey);

                if (PREFIX_LIST.equals(tokenPrefix)) {
                    // quote each element and split on ,
                    replacement = Arrays.asList(("'" + replacement.toString().replaceAll(",", "','") + "'").split(","));
                }

                if (replacement instanceof List) {
                    StringBuffer commaSeparated = new StringBuffer();
                    boolean first = true;
                    for (Object entry : ((List) replacement)) {
                        if (!first) {
                            commaSeparated.append(",");
                        } else {
                            first = false;
                        }
                        commaSeparated.append(entry.toString());
                    }
                    replacement = commaSeparated.toString();
                }
                if (replacement == null) {
                    replacement = "";
                }
                
                // Optional control of representation via prefix
                if (tokenPrefix != null) {
                    if (tokenPrefix.equals(PREFIX_UNQUOTED)) {
                        // Leave replacement unquoted
                    } else if (tokenPrefix.equals(PREFIX_DOTNOTATION)) {
                        // Convert Json Pointer to OrientDB dot notation
                        String dotDelimited = replacement.toString().replace('/', '.');
                        if (dotDelimited.startsWith(".")) {
                            replacement = dotDelimited.substring(1);
                        } else {
                            replacement = dotDelimited;
                        }
                    }
                } else {
                    // Default is single quoted string replacement
                    replacement = "'" + replacement + "'";
                }
                
                matcher.appendReplacement(buffer, "");
                buffer.append(replacement);
            }
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    /**
     * Replaces a query string with tokens of format ${token-name} with the 
     * token format in OrientDB, which is of the form :token-name.
     * 
     * OrientDB tokens has some limitations, e.g. they can currently only be used
     * in the where clause, and hence the returned string is not guaranteed to be
     * valid for use in a prepared statement. If the parsing fails the system may
     * have to fall back onto non-prepared statements and manual token replacement.
     * 
     * @param queryString the query with OpenIDM format tokens ${token}
     * @return the query with all tokens replaced with the OrientDB style tokens :token
     * @throws PrepareNotSupported if this method knows a given statement can not be converted into a prepared statement.
     * That a statement was not rejected here though does not mean it could not fail during the parsing phase later.
     */
    String replaceTokensWithOrientToken(String queryString) throws PrepareNotSupported {
        Matcher matcher = tokenPattern.matcher(queryString);
        StringBuffer buf = new StringBuffer();
        while (matcher.find()) {
            String origToken = matcher.group(1);
            
            String tokenKey = origToken;
            String tokenPrefix = null;
            String[] tokenKeyParts = tokenKey.split(":", 2);
            // if prefix found
            if (tokenKeyParts.length == 2) {
                tokenPrefix = tokenKeyParts[0];
                tokenKey = tokenKeyParts[1];
            }
            if (tokenPrefix != null && tokenPrefix.equals(PREFIX_DOTNOTATION)) {
                throw new PrepareNotSupported("Prepared query not supported for params with dotnotation conversion");
            }
            if (tokenKey != null && tokenKey.length() > 0) {
                // OrientDB token is of format :token-name
                String newToken = ":" + tokenKey;
                matcher.appendReplacement(buf, "");
                buf.append(newToken);
            }
        }
        matcher.appendTail(buf);
        return buf.toString();
    }
}