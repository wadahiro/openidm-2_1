/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions Copyrighted [year] [name of copyright owner]".
 *
 * Copyright © 2013 ForgeRock Inc. All rights reserved.
 */

package org.forgerock.openidm.filter;

import org.forgerock.json.fluent.JsonValue;
import org.forgerock.openidm.jaspi.modules.AuthData;

/**
 * Service interface for AuthFilter.
 */
public interface AuthFilterService {

    /**
     * Method for re-authenticating requests.  The request's security context will need to include a
     * "X-OpenIDM-Reauth-Password" header to succeed.
     *
     * @param request The request object.
     * @return The AuthData response object.
     * @throws AuthException If there is a problem re-authenticating.
     */
    public AuthData reauthenticate(JsonValue request) throws AuthException;
}
