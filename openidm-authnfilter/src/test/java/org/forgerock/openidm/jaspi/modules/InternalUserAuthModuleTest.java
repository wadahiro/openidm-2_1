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
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2013 ForgeRock Inc.
 */

package org.forgerock.openidm.jaspi.modules;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.security.auth.Subject;
import javax.security.auth.message.AuthException;
import javax.security.auth.message.AuthStatus;
import javax.security.auth.message.MessageInfo;
import javax.servlet.http.HttpServletRequest;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;
import static org.testng.Assert.assertEquals;

/**
 * @author Phill Cunnington
 */
public class InternalUserAuthModuleTest {

    private InternalUserAuthModule internalUserAuthModule;

    private AuthHelper authHelper;

    @BeforeMethod
    public void setUp() {

        authHelper = mock(AuthHelper.class);

        internalUserAuthModule = new InternalUserAuthModule(authHelper);
    }

    @Test
    public void shouldValidateRequestWhenUsernameHeaderIsNull() throws AuthException {

        //Given
        MessageInfo messageInfo = mock(MessageInfo.class);
        Subject clientSubject = new Subject();
        Subject serviceSubject = new Subject();
        AuthData authData = mock(AuthData.class);

        HttpServletRequest request = mock(HttpServletRequest.class);

        given(messageInfo.getRequestMessage()).willReturn(request);
        given(request.getHeader("X-OpenIDM-Username")).willReturn(null);
        given(request.getHeader("X-OpenIDM-Password")).willReturn("PASSWORD");

        //When
        AuthStatus authStatus = internalUserAuthModule.validateRequest(messageInfo, clientSubject, serviceSubject,
                authData);

        //Then
        verifyZeroInteractions(authHelper);
        verifyZeroInteractions(authData);
        assertEquals(authStatus, AuthStatus.SEND_FAILURE);
    }

    @Test
    public void shouldValidateRequestWhenUsernameHeaderIsEmptyString() throws AuthException {

        //Given
        MessageInfo messageInfo = mock(MessageInfo.class);
        Subject clientSubject = new Subject();
        Subject serviceSubject = new Subject();
        AuthData authData = mock(AuthData.class);

        HttpServletRequest request = mock(HttpServletRequest.class);

        given(messageInfo.getRequestMessage()).willReturn(request);
        given(request.getHeader("X-OpenIDM-Username")).willReturn("");
        given(request.getHeader("X-OpenIDM-Password")).willReturn("PASSWORD");

        //When
        AuthStatus authStatus = internalUserAuthModule.validateRequest(messageInfo, clientSubject, serviceSubject,
                authData);

        //Then
        verifyZeroInteractions(authHelper);
        verify(authData).setResource("internal/user");
        assertEquals(authStatus, AuthStatus.SEND_FAILURE);
    }

    @Test
    public void shouldValidateRequestWhenPasswordHeaderIsNull() throws AuthException {

        //Given
        MessageInfo messageInfo = mock(MessageInfo.class);
        Subject clientSubject = new Subject();
        Subject serviceSubject = new Subject();
        AuthData authData = mock(AuthData.class);

        HttpServletRequest request = mock(HttpServletRequest.class);

        given(messageInfo.getRequestMessage()).willReturn(request);
        given(request.getHeader("X-OpenIDM-Username")).willReturn("USERNAME");
        given(request.getHeader("X-OpenIDM-Password")).willReturn(null);

        //When
        AuthStatus authStatus = internalUserAuthModule.validateRequest(messageInfo, clientSubject, serviceSubject,
                authData);

        //Then
        verifyZeroInteractions(authHelper);
        verify(authData).setResource("internal/user");
        assertEquals(authStatus, AuthStatus.SEND_FAILURE);
    }

    @Test
    public void shouldValidateRequestWhenPasswordHeaderIsEmptyString() throws AuthException {

        //Given
        MessageInfo messageInfo = mock(MessageInfo.class);
        Subject clientSubject = new Subject();
        Subject serviceSubject = new Subject();
        AuthData authData = mock(AuthData.class);

        HttpServletRequest request = mock(HttpServletRequest.class);

        given(messageInfo.getRequestMessage()).willReturn(request);
        given(request.getHeader("X-OpenIDM-Username")).willReturn("USERNAME");
        given(request.getHeader("X-OpenIDM-Password")).willReturn("");

        //When
        AuthStatus authStatus = internalUserAuthModule.validateRequest(messageInfo, clientSubject, serviceSubject,
                authData);

        //Then
        verifyZeroInteractions(authHelper);
        verify(authData).setResource("internal/user");
        assertEquals(authStatus, AuthStatus.SEND_FAILURE);
    }

    @Test
    public void shouldValidateRequestWhenAuthenticationSuccessful() throws AuthException {

        //Given
        MessageInfo messageInfo = mock(MessageInfo.class);
        Subject clientSubject = new Subject();
        Subject serviceSubject = new Subject();
        AuthData authData = mock(AuthData.class);

        HttpServletRequest request = mock(HttpServletRequest.class);

        given(messageInfo.getRequestMessage()).willReturn(request);
        given(request.getHeader("X-OpenIDM-Username")).willReturn("USERNAME");
        given(request.getHeader("X-OpenIDM-Password")).willReturn("PASSWORD");

        given(authHelper.authenticate("credential-internaluser-query", "internal/user", "USERNAME", "PASSWORD",
                authData)).willReturn(true);

        //When
        AuthStatus authStatus = internalUserAuthModule.validateRequest(messageInfo, clientSubject, serviceSubject,
                authData);

        //Then
        verify(authData).setUsername("USERNAME");
        verify(authData).setResource("internal/user");
        assertEquals(authStatus, AuthStatus.SUCCESS);
    }

    @Test
    public void shouldValidateRequestWhenAuthenticationFailed() throws AuthException {

        //Given
        MessageInfo messageInfo = mock(MessageInfo.class);
        Subject clientSubject = new Subject();
        Subject serviceSubject = new Subject();
        AuthData authData = mock(AuthData.class);

        HttpServletRequest request = mock(HttpServletRequest.class);

        given(messageInfo.getRequestMessage()).willReturn(request);
        given(request.getHeader("X-OpenIDM-Username")).willReturn("USERNAME");
        given(request.getHeader("X-OpenIDM-Password")).willReturn("PASSWORD");

        given(authHelper.authenticate("credential-internaluser-query", "internal/user", "USERNAME", "PASSWORD",
                authData)).willReturn(false);

        //When
        AuthStatus authStatus = internalUserAuthModule.validateRequest(messageInfo, clientSubject, serviceSubject,
                authData);

        //Then
        verify(authData).setUsername("USERNAME");
        verify(authData).setResource("internal/user");
        assertEquals(authStatus, AuthStatus.SEND_FAILURE);
    }

    @Test
    public void shouldSecureResponseIfAnonymousUserSetSkipSession() throws AuthException {

        //Given
        MessageInfo messageInfo = mock(MessageInfo.class);
        Map<String, Object> messageInfoMap = new HashMap<String, Object>();
        Map<String, Object> contextMap = new HashMap<String, Object>();
        Subject serviceSubject = new Subject();
        messageInfoMap.put(IDMServerAuthModule.CONTEXT_REQUEST_KEY, contextMap);

        HttpServletRequest request = mock(HttpServletRequest.class);

        given(messageInfo.getRequestMessage()).willReturn(request);
        given(request.getHeader("X-OpenIDM-Username")).willReturn("anonymous");
        given(messageInfo.getMap()).willReturn(messageInfoMap);

        //When
        AuthStatus authStatus = internalUserAuthModule.secureResponse(messageInfo, serviceSubject);

        //Then
        assertEquals(authStatus, AuthStatus.SEND_SUCCESS);
        assertEquals(messageInfoMap.get("skipSession"), true);
    }

    @Test
    public void shouldSecureResponseIfNotAnonymousUser() throws AuthException {

        //Given
        MessageInfo messageInfo = mock(MessageInfo.class);
        Map<String, Object> messageInfoMap = new HashMap<String, Object>();
        Map<String, Object> contextMap = new HashMap<String, Object>();
        Subject serviceSubject = new Subject();
        messageInfoMap.put(IDMServerAuthModule.CONTEXT_REQUEST_KEY, contextMap);

        HttpServletRequest request = mock(HttpServletRequest.class);

        given(messageInfo.getRequestMessage()).willReturn(request);
        given(request.getHeader("X-OpenIDM-Username")).willReturn("USERNAME");
        given(messageInfo.getMap()).willReturn(messageInfoMap);

        //When
        AuthStatus authStatus = internalUserAuthModule.secureResponse(messageInfo, serviceSubject);

        //Then
        assertEquals(authStatus, AuthStatus.SEND_SUCCESS);
    }
}
