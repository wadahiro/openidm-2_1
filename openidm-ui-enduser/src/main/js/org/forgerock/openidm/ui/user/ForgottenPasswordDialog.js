/**
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

/*global $, _, define*/

define("org/forgerock/openidm/ui/user/ForgottenPasswordDialog", [
    "org/forgerock/commons/ui/common/components/Dialog",
    "org/forgerock/commons/ui/common/main/ValidatorsManager",
    "UserDelegate",
    "org/forgerock/commons/ui/common/main/EventManager",
    "org/forgerock/commons/ui/common/util/Constants",
    "org/forgerock/commons/ui/common/main/Configuration",
    "org/forgerock/openidm/ui/user/delegates/SecurityQuestionDelegate"
], function(Dialog, validatorsManager, userDelegate, eventManager, constants, conf, securityQuestionDelegate) {
    var ForgottenPasswordDialog = Dialog.extend({    
        contentTemplate: "templates/user/ForgottenPasswordTemplate.html",
        baseTemplate: "templates/common/LoginBaseTemplate.html",
        
        events: {
            "click .dialogActions input[type=submit]": "formSubmit",
            "click .dialogCloseCross img": "close",
            "click input[name='close']": "close",
            "click input[name='submitAnswer']": "submitAnswer",
            "click .dialogContainer": "stop",
            "onValidate": "onValidate",
            "customValidate": "customValidate",
            "userNameFound": "userNameFound",
            "userNameNotFound": "userNameNotFound"
        },
        
        data: {         
            width: 800,
            height: 400
        },
        
        heights : {
            "userName": 210,
            "securityAnswer": 300,
            "password": 400
        },
        
        securityQuestions: {},
        
        render: function() {
            var securityQuestionRef;
            this.securityQuestions = {};
            this.actions = {};
            this.addAction($.t("common.form.update"), "submit");
            this.show(_.bind(function() {
                validatorsManager.bindValidators(this.$el); 
                this.$el.find(".dialogActions input[type=submit]").hide();
                if (conf.forgottenPasswordUserName) {
                    this.$el.find("input[name=resetUsername]").val(conf.forgottenPasswordUserName);
                    this.$el.find("input[name=resetUsername]").trigger("change");
                    delete conf.forgottenPasswordUserName;
                }
                this.data.height = this.heights.userName;
                this.resize();
            }, this));
            
            securityQuestionRef = this.securityQuestions;
            securityQuestionDelegate.getAllSecurityQuestions(function(secquestions) {
                $.each(secquestions, function(i,item){
                    securityQuestionRef[item.key] = item.value;
                });
            });
        },
        
        formSubmit: function(event) {
            event.preventDefault();
            event.stopPropagation();
            
            if (validatorsManager.formValidated(this.$el)) {
                this.changePassword();
            } else {
                var errorMessage = this.$el.find("input[name=resetUsername][data-validation-status=error]"),userName = this.$el.find("input[name=resetUsername]").val(), securityQuestionRef;
                if (errorMessage.length !== 0) {
                    this.$el.find("#fgtnAnswerDiv").hide();
                    this.$el.find("input[name=fgtnSecurityAnswer]").val("");
                    this.$el.find("input[name=password]").val("");
                    this.$el.find("input[name=passwordConfirm]").val("");
                    this.data.height = this.heights.userName;
                    this.resize();
                }
            }
        },
        userNameFound: function (event, securityQuestion) {
            console.log(this.securityQuestions[securityQuestion]);
            $("#fgtnSecurityQuestion").text(this.securityQuestions[securityQuestion]);
            this.data.height = this.heights.securityAnswer;
            this.resize();
            this.$el.find("#fgtnAnswerDiv").show();
            this.$el.find("input[name=fgtnSecurityAnswer]").focus();
        },
        userNameNotFound: function () {
            $("#fgtnAnswerDiv, #fgtnPasswordDiv").hide();
            this.$el.find(".dialogActions input[type=submit]").hide();
            this.data.height = this.heights.userName;
            this.resize();
            this.$el.find("input[name=resetUsername]").focus();
        },
        changePassword: function() {
            var dialog = this, userName = this.$el.find("input[name=resetUsername]").val(), securityAnswer = this.$el.find("input[name=fgtnSecurityAnswer]").val(), newPassword = this.$el.find("input[name=password]").val();
            console.log("changing password");
            
            userDelegate.setNewPassword(userName, securityAnswer, newPassword, function(r) {
                eventManager.sendEvent(constants.FORGOTTEN_PASSWORD_CHANGED_SUCCESSFULLY, { userName: userName, password: newPassword});
                dialog.close();
            },
            function (r) {
                console.log("Failed to set password for some reason....");
                console.log(r);
            } 
            );
        },
        submitAnswer : function () {
            this.$el.find("input[name=fgtnSecurityAnswer]").trigger("validate");
        },
        customValidate: function(event, input, msg, validatorType) {

            if (validatorType === "securityAnswer") {
                
                if (typeof(msg) === "undefined") {
                    validatorsManager.bindValidators(this.$el.find('#fgtnPasswordDiv'), userDelegate.baseEntity + "/" + this.$el.find("input[name=_id]").val(), _.bind(function () {
                        this.$el.trigger("onValidate");
                        this.$el.find("#fgtnPasswordDiv").show();
                        this.$el.find(".dialogActions input[type=submit]").show();
                        this.data.height = this.heights.password;
                        this.resize();
                    }, this)); 
                }
                else {
                    this.$el.find("#fgtnPasswordDiv").hide();
                    this.data.height = this.heights.securityAnswer;
                    this.resize();
                    this.$el.find("input[name=_id]").val("");
                }
                
            }
            
        }
    }); 
    
    return new ForgottenPasswordDialog();
});

