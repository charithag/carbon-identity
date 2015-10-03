<!--
~ Copyright (c) 2005-2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
~
~ WSO2 Inc. licenses this file to you under the Apache License,
~ Version 2.0 (the "License"); you may not use this file except
~ in compliance with the License.
~ You may obtain a copy of the License at
~
~ http://www.apache.org/licenses/LICENSE-2.0
~
~ Unless required by applicable law or agreed to in writing,
~ software distributed under the License is distributed on an
~ "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
~ KIND, either express or implied. See the License for the
~ specific language governing permissions and limitations
~ under the License.
-->

<%@page import="org.apache.axis2.context.ConfigurationContext"%>
<%@page import="org.wso2.carbon.CarbonConstants"%>
<%@ page import="org.wso2.carbon.identity.application.common.model.idp.xsd.IdentityProvider" %>
<%@ page import="org.wso2.carbon.idp.mgt.ui.client.IdentityProviderMgtServiceClient" %>
<%@ page import="org.wso2.carbon.idp.mgt.ui.util.IdPManagementUIUtil" %>
<%@ page import="org.wso2.carbon.ui.CarbonUIMessage" %>
<%@ page import="org.wso2.carbon.ui.CarbonUIUtil" %>
<%@ page import="org.wso2.carbon.ui.util.CharacterEncoder" %>
<%@ page import="org.wso2.carbon.utils.ServerConstants" %>
<%@ page import="java.text.MessageFormat" %>
<%@ page import="java.util.ResourceBundle" %>

<%
	String BUNDLE = "org.wso2.carbon.idp.mgt.ui.i18n.Resources";
    ResourceBundle resourceBundle = ResourceBundle.getBundle(BUNDLE, request.getLocale());
    try {
        String cookie = (String) session.getAttribute(ServerConstants.ADMIN_SERVICE_COOKIE);
        String backendServerURL = CarbonUIUtil.getServerURL(config.getServletContext(), session);
        ConfigurationContext configContext =
                (ConfigurationContext) config.getServletContext().getAttribute(CarbonConstants.CONFIGURATION_CONTEXT);
        IdentityProviderMgtServiceClient client = new IdentityProviderMgtServiceClient(cookie, backendServerURL, configContext);

        IdentityProvider identityProvider = null;
        String oldIdpName = null;
        
      	if (request.getParameter("idPName") != null	&& request.getParameter("idPName").length() != 0 && request.getParameter("enable") != null) {

			identityProvider = client.getIdPByName(request.getParameter("idPName"));

    		if (request.getParameter("enable").equals("1")) {
    			identityProvider.setEnable(true);
    		} else {
    			identityProvider.setEnable(false);
    		}
    		
			oldIdpName = request.getParameter("idPName");
		} else {
	        identityProvider = IdPManagementUIUtil.buildeFederatedIdentityProvider(request);
	        oldIdpName = ((IdentityProvider) session.getAttribute("identityProvider")).getIdentityProviderName();
		}
      		
		client.updateIdP(oldIdpName, identityProvider);

      	
		String message = MessageFormat.format(resourceBundle.getString("success.updating.idp"),	null);
		CarbonUIMessage.sendCarbonUIMessage(message, CarbonUIMessage.INFO, request);
	} catch (Exception e) {
		String message = MessageFormat.format(
				resourceBundle.getString("error.updating.idp"),
				new Object[] { e.getMessage() });
		CarbonUIMessage.sendCarbonUIMessage(message,
				CarbonUIMessage.ERROR, request);
	} finally {
		session.removeAttribute("identityProvider");
		session.removeAttribute("identityProviderList");
	}
%>
<script type="text/javascript">
    location.href = "idp-mgt-list-load.jsp";
</script>
