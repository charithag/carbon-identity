/*
*  Copyright (c) 2005-2010, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
*  WSO2 Inc. licenses this file to you under the Apache License,
*  Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License.
*  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/
package org.wso2.carbon.identity.user.store.configuration;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.wso2.carbon.context.CarbonContext;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.core.AbstractAdmin;
import org.wso2.carbon.identity.user.store.configuration.beans.RandomPassword;
import org.wso2.carbon.identity.user.store.configuration.beans.RandomPasswordContainer;
import org.wso2.carbon.identity.user.store.configuration.cache.RandomPasswordContainerCache;
import org.wso2.carbon.identity.user.store.configuration.dto.PropertyDTO;
import org.wso2.carbon.identity.user.store.configuration.dto.UserStoreDTO;
import org.wso2.carbon.identity.user.store.configuration.internal.UserStoreConfigListenersHolder;
import org.wso2.carbon.identity.user.store.configuration.listener.UserStoreConfigListener;
import org.wso2.carbon.identity.user.store.configuration.utils.IdentityUserStoreMgtException;
import org.wso2.carbon.identity.user.store.configuration.utils.SecondaryUserStoreConfigurationUtil;
import org.wso2.carbon.identity.user.store.configuration.utils.UserStoreConfigurationConstant;
import org.wso2.carbon.ndatasource.common.DataSourceException;
import org.wso2.carbon.ndatasource.core.DataSourceManager;
import org.wso2.carbon.ndatasource.core.services.WSDataSourceMetaInfo;
import org.wso2.carbon.ndatasource.core.services.WSDataSourceMetaInfo.WSDataSourceDefinition;
import org.wso2.carbon.ndatasource.rdbms.RDBMSConfiguration;
import org.wso2.carbon.user.api.Properties;
import org.wso2.carbon.user.api.Property;
import org.wso2.carbon.user.api.RealmConfiguration;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.user.core.UserCoreConstants;
import org.wso2.carbon.user.core.UserStoreConfigConstants;
import org.wso2.carbon.user.core.common.AbstractUserStoreManager;
import org.wso2.carbon.user.core.config.XMLProcessorUtils;
import org.wso2.carbon.user.core.jdbc.JDBCRealmConstants;
import org.wso2.carbon.user.core.tenant.TenantCache;
import org.wso2.carbon.user.core.tenant.TenantIdKey;
import org.wso2.carbon.user.core.tracker.UserStoreManagerRegistry;
import org.wso2.carbon.user.core.util.UserCoreUtil;
import org.wso2.carbon.utils.CarbonUtils;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class UserStoreConfigAdminService extends AbstractAdmin {
    public static final Log log = LogFactory.getLog(UserStoreConfigAdminService.class);
    public static final String DISABLED = "Disabled";
    public static final String DESCRIPTION = "Description";
    public static final String USERSTORES = "userstores";
    private static final String deploymentDirectory = CarbonUtils.getCarbonRepository() + USERSTORES;
    XMLProcessorUtils xmlProcessorUtils = new XMLProcessorUtils();

    /**
     * Get details of current secondary user store configurations
     *
     * @return : Details of all the configured secondary user stores
     * @throws UserStoreException
     */
    public UserStoreDTO[] getSecondaryRealmConfigurations() throws UserStoreException {
        ArrayList<UserStoreDTO> domains = new ArrayList<UserStoreDTO>();
        RealmConfiguration secondaryRealmConfiguration = CarbonContext.getThreadLocalCarbonContext().getUserRealm().
                getRealmConfiguration().getSecondaryRealmConfig();
        //not editing primary store
        if (secondaryRealmConfiguration == null) {
            return null;
        } else {

            do {
                Map<String, String> userStoreProperties = secondaryRealmConfiguration.getUserStoreProperties();
                UserStoreDTO userStoreDTO = new UserStoreDTO();

                String uuid = userStoreProperties.get(UserStoreConfigurationConstant.UNIQUE_ID_CONSTANT);
                if (uuid == null) {
                    uuid = UUID.randomUUID().toString();
                }

                String randomPhrase = UserStoreConfigurationConstant.RANDOM_PHRASE_PREFIX + uuid;
                String className = secondaryRealmConfiguration.getUserStoreClass();
                userStoreDTO.setClassName(secondaryRealmConfiguration.getUserStoreClass());
                userStoreDTO.setDescription(secondaryRealmConfiguration.getUserStoreProperty(DESCRIPTION));
                userStoreDTO.setDomainId(secondaryRealmConfiguration.getUserStoreProperty(UserStoreConfigConstants.DOMAIN_NAME));
                if (userStoreProperties.get(DISABLED) != null) {
                    userStoreDTO.setDisabled(Boolean.valueOf(userStoreProperties.get(DISABLED)));
                }
                userStoreProperties.put("Class", className);
                userStoreProperties.put(UserStoreConfigurationConstant.UNIQUE_ID_CONSTANT, uuid);
                RandomPassword[] randomPasswords = getRandomPasswordProperties(className, randomPhrase,
                        secondaryRealmConfiguration);
                if (randomPasswords != null) {
                    updatePasswordContainer(randomPasswords, uuid);
                }

                String originalPassword = null;
                if (userStoreProperties.containsKey(UserStoreConfigConstants.connectionPassword)) {
                    originalPassword = userStoreProperties.get(UserStoreConfigConstants.connectionPassword);
                    userStoreProperties.put(UserStoreConfigConstants.connectionPassword, randomPhrase);
                }
                if (userStoreProperties.containsKey(JDBCRealmConstants.PASSWORD)) {
                    originalPassword = userStoreProperties.get(JDBCRealmConstants.PASSWORD);
                    userStoreProperties.put(JDBCRealmConstants.PASSWORD, randomPhrase);
                }
                userStoreDTO.setProperties(convertMapToArray(userStoreProperties));

                //Now revert back to original password
                if (userStoreProperties.containsKey(UserStoreConfigConstants.connectionPassword)) {
                    if (originalPassword != null) {
                        userStoreProperties.put(UserStoreConfigConstants.connectionPassword, originalPassword);
                    }
                }
                if (userStoreProperties.containsKey(JDBCRealmConstants.PASSWORD)) {
                    if (originalPassword != null) {
                        userStoreProperties.put(JDBCRealmConstants.PASSWORD, originalPassword);
                    }
                }

                domains.add(userStoreDTO);
                secondaryRealmConfiguration = secondaryRealmConfiguration.getSecondaryRealmConfig();

            } while (secondaryRealmConfiguration != null);
        }
        return domains.toArray(new UserStoreDTO[domains.size()]);
    }

    /**
     * Get user store properties of a given active user store manager as an array
     *
     * @param properties: properties of the user store
     * @return key#value
     */
    private PropertyDTO[] convertMapToArray(Map<String, String> properties) throws UserStoreException {
        Set<Map.Entry<String, String>> propertyEntries = properties.entrySet();
        ArrayList<PropertyDTO> propertiesList = new ArrayList<PropertyDTO>();
        String key;
        String value;
        for (Map.Entry<String, String> entry : propertyEntries) {
            key = (String) entry.getKey();
            value = (String) entry.getValue();
            PropertyDTO propertyDTO = new PropertyDTO(key, value);
            propertiesList.add(propertyDTO);
        }
        return propertiesList.toArray(new PropertyDTO[propertiesList.size()]);
    }

    /**
     * Get available user store manager implementations
     *
     * @return: Available implementations for user store managers
     */
    public String[] getAvailableUserStoreClasses() throws UserStoreException {
        Set<String> classNames = UserStoreManagerRegistry.getUserStoreManagerClasses();
        return classNames.toArray(new String[classNames.size()]);
    }

    /**
     * Get User Store Manager default properties for a given implementation
     *
     * @param className:Implementation class name for the user store
     * @return : list of default properties(mandatory+optional)
     */
    public Properties getUserStoreManagerProperties(String className) throws UserStoreException {
        return (Properties) UserStoreManagerRegistry.getUserStoreProperties(className);
    }

    /**
     * Save the sent configuration to xml file
     *
     * @param userStoreDTO: Represent the configuration of user store
     * @throws DataSourceException
     * @throws TransformerException
     * @throws ParserConfigurationException
     */
    public void addUserStore(UserStoreDTO userStoreDTO) throws UserStoreException, DataSourceException {
        String domainName = userStoreDTO.getDomainId();
        xmlProcessorUtils.isValidDomain(domainName, true);

        File userStoreConfigFile = createConfigurationFile(domainName);
        // This is a redundant check
        if (userStoreConfigFile.exists()) {
            String msg = "Cannot add user store " + domainName + ". User store already exists.";
            throw new UserStoreException(msg);
        }
        writeUserMgtXMLFile(userStoreConfigFile, userStoreDTO, false);
        if (log.isDebugEnabled()) {
            log.debug("New user store successfully written to the file" + userStoreConfigFile.getAbsolutePath());
        }
    }


    /**
     * Edit currently existing user store
     *
     * @param userStoreDTO: Represent the configuration of user store
     * @throws DataSourceException
     * @throws TransformerException
     * @throws ParserConfigurationException
     */
    public void editUserStore(UserStoreDTO userStoreDTO) throws UserStoreException, DataSourceException {
        String domainName = userStoreDTO.getDomainId();
        if (xmlProcessorUtils.isValidDomain(domainName, false)) {

            File userStoreConfigFile = createConfigurationFile(domainName);
            if (!userStoreConfigFile.exists()) {
                String msg = "Cannot edit user store " + domainName + ". User store cannot be edited.";
                throw new UserStoreException(msg);
            }

            writeUserMgtXMLFile(userStoreConfigFile, userStoreDTO, true);
            if (log.isDebugEnabled()) {
                log.debug("Edited user store successfully written to the file" + userStoreConfigFile.getAbsolutePath());
            }
        } else {
            throw new UserStoreException("Trying to edit an invalid domain");
        }
    }

    /**
     * Edit currently existing user store with a change of its domain name
     *
     * @param userStoreDTO:      Represent the configuration of new user store
     * @param previousDomainName
     * @throws DataSourceException
     * @throws TransformerException
     * @throws ParserConfigurationException
     */
    public void editUserStoreWithDomainName(String previousDomainName, UserStoreDTO userStoreDTO) throws UserStoreException, DataSourceException {
        boolean isDebugEnabled = log.isDebugEnabled();
        String domainName = userStoreDTO.getDomainId();
        if (isDebugEnabled) {
            log.debug("Changing user store " + previousDomainName + " to " + domainName);
        }

        File userStoreConfigFile = null;
        File previousUserStoreConfigFile = null;

        String fileName = domainName.replace(".", "_");
        String previousFileName = previousDomainName.replace(".", "_");

        int tenantId = CarbonContext.getThreadLocalCarbonContext().getTenantId();

        if (tenantId == MultitenantConstants.SUPER_TENANT_ID) {
            File userStore = new File(deploymentDirectory);
            if (!userStore.exists()) {
                if (new File(deploymentDirectory).mkdir()) {
                    //folder 'userstores' created
                    log.info("folder 'userstores' created for  super tenant " + fileName);
                } else {
                    log.error("Error at creating 'userstores' directory to store configurations for super tenant");
                }
            }
            userStoreConfigFile = new File(deploymentDirectory + File.separator + fileName + ".xml");
            previousUserStoreConfigFile = new File(deploymentDirectory + File.separator + previousFileName + ".xml");
        } else {
            String tenantFilePath = CarbonUtils.getCarbonTenantsDirPath();
            tenantFilePath = tenantFilePath + File.separator + tenantId + File.separator + USERSTORES;
            File userStore = new File(tenantFilePath);
            if (!userStore.exists()) {
                if (new File(tenantFilePath).mkdir()) {
                    //folder 'userstores' created
                    log.info("folder 'userstores' created for tenant " + tenantId);
                } else {
                    log.error("Error at creating 'userstores' directory to store configurations for tenant:" + tenantId);
                }
            }
            userStoreConfigFile = new File(tenantFilePath + File.separator + fileName + ".xml");
            previousUserStoreConfigFile = new File(tenantFilePath + File.separator + previousFileName + ".xml");
        }

        if (!previousUserStoreConfigFile.exists()) {
            String msg = "Cannot update user store domain name. Previous domain name " + previousDomainName + " does not exists.";
            throw new UserStoreException(msg);
        }

        if (userStoreConfigFile.exists()) {
            String msg = "Cannot update user store domain name. An user store already exists with new domain " + domainName + ".";
            throw new UserStoreException(msg);
        }

        // Run pre user-store name update listeners
        List<UserStoreConfigListener> userStoreConfigListeners = UserStoreConfigListenersHolder.getInstance()
                .getUserStoreConfigListeners();
        for (UserStoreConfigListener userStoreConfigListener : userStoreConfigListeners) {
            userStoreConfigListener.onUserStoreNamePreUpdate(CarbonContext.getThreadLocalCarbonContext().getTenantId
                    (), previousDomainName, domainName);
        }

        // Update persisted domain name
        AbstractUserStoreManager userStoreManager = (AbstractUserStoreManager) CarbonContext.getThreadLocalCarbonContext().getUserRealm().getUserStoreManager();
        userStoreManager.updatePersistedDomain(previousDomainName, domainName);
        if (log.isDebugEnabled()) {
            log.debug("Renamed persisted domain name from" + previousDomainName + " to " + domainName + " of tenant:" + tenantId + " from UM_DOMAIN.");
        }

        previousUserStoreConfigFile.delete();
        writeUserMgtXMLFile(userStoreConfigFile, userStoreDTO, true);
    }

    /**
     * Deletes the user store specified
     *
     * @param domainName: domain name of the user stores to be deleted
     */
    public void deleteUserStore(String domainName) throws UserStoreException {
        if (!isAuthorized()) {
            throw new UserStoreException("Logged user is not authorized to delete user stores");
        }
        deleteUserStoresSet(new String[]{domainName});
    }

    /**
     * Delete the given list of user stores
     *
     * @param domains: domain names of user stores to be deleted
     */
    public void deleteUserStoresSet(String[] domains) throws UserStoreException {
        boolean isDebugEnabled = log.isDebugEnabled();

        if (domains == null || domains.length <= 0) {
            throw new UserStoreException("No selected user stores to delete");
        }

        if (!validateDomainsForDelete(domains)) {
            if (log.isDebugEnabled()) {
                log.debug("Failed to delete user store : No privileges to delete own user store configurations ");
            }
            throw new UserStoreException("No privileges to delete own user store configurations.");
        }

        int tenantId = CarbonContext.getThreadLocalCarbonContext().getTenantId();
        String path;
        if (tenantId == MultitenantConstants.SUPER_TENANT_ID) {
            path = deploymentDirectory;
        } else {
            path = CarbonUtils.getCarbonTenantsDirPath() + File.separator + tenantId + File.separator + USERSTORES;

        }
        File file = new File(path);
        for (String domainName : domains) {
            if (isDebugEnabled) {
                log.debug("Deleting, .... " + domainName + " domain.");
            }

            // Run pre user-store name update listeners
            List<UserStoreConfigListener> userStoreConfigListeners = UserStoreConfigListenersHolder.getInstance()
                    .getUserStoreConfigListeners();
            for (UserStoreConfigListener userStoreConfigListener : userStoreConfigListeners) {
                userStoreConfigListener.onUserStorePreDelete(CarbonContext.getThreadLocalCarbonContext().getTenantId
                        (), domainName);
            }

            // Delete persisted domain name
            AbstractUserStoreManager userStoreManager = (AbstractUserStoreManager) CarbonContext.getThreadLocalCarbonContext().getUserRealm().getUserStoreManager();
            //userStoreManager.deletePersistedDomain(domainName);

            userStoreManager.deletePersistedDomain(domainName);
            if (isDebugEnabled) {
                log.debug("Removed persisted domain name: " + domainName + " of tenant:" + tenantId + " from " +
                        "UM_DOMAIN.");
            }
            //clear cache to make the modification effective
            UserCoreUtil.getRealmService().clearCachedUserRealm(tenantId);
            TenantCache.getInstance().clearCacheEntry(new TenantIdKey(tenantId));

            // Delete file
            deleteFile(file, domainName.replace(".", "_").concat(".xml"));
        }
    }

    private boolean validateDomainsForDelete(String[] domains) {
        String userDomain = UserCoreUtil.extractDomainFromName(PrivilegedCarbonContext.getThreadLocalCarbonContext()
                .getUsername());
        for (String domain : domains) {
            if (domain.equalsIgnoreCase(userDomain)) {
                //Trying to delete own domain
                return false;
            }
        }
        return true;

    }

    /**
     * Adds an array of properties
     *
     * @param propertyDTOs : List of user store properties
     * @param doc:         Document
     * @param parent       : Parent element of the properties to be added
     */
    private void addProperties(String userStoreClass, PropertyDTO[] propertyDTOs, Document doc, Element parent,
                               boolean editSecondaryUserStore) throws UserStoreException {

        RandomPasswordContainer randomPasswordContainer = null;
        if (editSecondaryUserStore) {
            String uniqueID = getUniqueIDFromUserDTO(propertyDTOs);
            randomPasswordContainer = getAndRemoveRandomPasswordContainer(uniqueID);
            if (randomPasswordContainer == null) {
                String errorMsg = "randomPasswordContainer is null for uniqueID therefore " +
                        "proceeding without encryption=" + uniqueID;
                log.error(errorMsg);//need this error log to further identify the reason for throwing this exception
                throw new UserStoreException("Longer delay causes the edit operation be to " +
                        "abandoned");
            }
        }
        //First check for mandatory field with #encrypt
        Property[] mandatoryProperties = getMandatoryProperties(userStoreClass);
        for (PropertyDTO propertyDTO : propertyDTOs) {
            String propertyDTOName = propertyDTO.getName();
            if (UserStoreConfigurationConstant.UNIQUE_ID_CONSTANT.equalsIgnoreCase(propertyDTOName)) {
                continue;
            }

            String propertyDTOValue = propertyDTO.getValue();
            if (propertyDTOValue != null) {
                boolean encrypted = false;
                if (isPropertyToBeEncrypted(mandatoryProperties, propertyDTOName)) {
                    if (randomPasswordContainer != null) {
                        RandomPassword randomPassword = getRandomPassword(randomPasswordContainer, propertyDTOName);
                        if (randomPassword != null) {
                            if (propertyDTOValue.equalsIgnoreCase(randomPassword.getRandomPhrase())) {
                                propertyDTOValue = randomPassword.getPassword();
                            }
                        }
                    }

                    try {
                        propertyDTOValue = SecondaryUserStoreConfigurationUtil.encryptPlainText(propertyDTOValue);
                        encrypted = true;
                    } catch (IdentityUserStoreMgtException e) {
                        log.error("addProperties failed to encrypt", e);
                        //its ok to continue from here
                    }
                }
                addProperty(propertyDTOName, propertyDTOValue, doc, parent, encrypted);
            }
        }
    }

    /**
     * Adds a property
     *
     * @param name:   Name of property
     * @param value:  Value
     * @param doc:    Document
     * @param parent: Parent element of the property to be added as a child
     */
    private void addProperty(String name, String value, Document doc, Element parent, boolean encrypted) {
        Element property = doc.createElement("Property");
        Attr attr;
        if (encrypted) {
            attr = doc.createAttribute("encrypted");
            attr.setValue("true");
            property.setAttributeNode(attr);
        }

        attr = doc.createAttribute("name");
        attr.setValue(name);
        property.setAttributeNode(attr);

        property.setTextContent(value);
        parent.appendChild(property);
    }


    private void deleteFile(File file, final String userStoreName) {
        File[] deleteCandidates = file.listFiles(new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.equalsIgnoreCase(userStoreName);
            }
        });
        for (File file1 : deleteCandidates) {
            if (file1.delete()) {
                log.info("File " + file.getName() + " deleted successfully");
            } else {
                log.error("error at deleting file:" + file.getName());
            }
        }
    }

    /**
     * Update a domain to be disabled/enabled
     *
     * @param domain:   Name of the domain to be updated
     * @param isDisable : Whether to disable/enable domain(true/false)
     */
    public void changeUserStoreState(String domain, Boolean isDisable) throws UserStoreException, Exception {

        File userStoreConfigFile = createConfigurationFile(domain);
        StreamResult result = new StreamResult(userStoreConfigFile);
        if (!userStoreConfigFile.exists()) {
            String msg = "Cannot edit user store." + domain + " does not exist.";
            throw new UserStoreException(msg);
        }

        DocumentBuilderFactory documentFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder documentBuilder = documentFactory.newDocumentBuilder();
        Document doc = documentBuilder.parse(userStoreConfigFile);

        NodeList elements = doc.getElementsByTagName("Property");
        for (int i = 0; i < elements.getLength(); i++) {
            //Assumes a property element only have attribute 'name'
            if ("Disabled".compareToIgnoreCase(elements.item(i).getAttributes().item(0).getNodeValue()) == 0) {
                elements.item(i).setTextContent(String.valueOf(isDisable));
                break;
            }
        }

        DOMSource source = new DOMSource(doc);

        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.METHOD, "xml");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "6");
        transformer.transform(source, result);

        if (log.isDebugEnabled()) {
            log.debug("New state :" + isDisable + " of the user store \'" + domain + "\' successfully written to the file system");
        }
    }

    public boolean testRDBMSConnection(String domainName, String driverName, String connectionURL, String username,
                                       String connectionPassword) throws DataSourceException {

        WSDataSourceMetaInfo wSDataSourceMetaInfo = new WSDataSourceMetaInfo();

        RDBMSConfiguration rdbmsConfiguration = new RDBMSConfiguration();
        rdbmsConfiguration.setUrl(connectionURL);
        rdbmsConfiguration.setUsername(username);
        rdbmsConfiguration.setPassword(connectionPassword);
        rdbmsConfiguration.setDriverClassName(driverName);

        WSDataSourceDefinition wSDataSourceDefinition = new WSDataSourceDefinition();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JAXBContext context;
        try {
            context = JAXBContext.newInstance(RDBMSConfiguration.class);
            Marshaller marshaller = context.createMarshaller();
            marshaller.marshal(rdbmsConfiguration, out);

        } catch (JAXBException e) {
            log.error("Error in marshelling User Store Configuration info", e);
        }
        wSDataSourceDefinition.setDsXMLConfiguration(out.toString());
        wSDataSourceDefinition.setType("RDBMS");

        wSDataSourceMetaInfo.setName(domainName);
        wSDataSourceMetaInfo.setDefinition(wSDataSourceDefinition);
        try {
            return DataSourceManager.getInstance().getDataSourceRepository().testDataSourceConnection(wSDataSourceMetaInfo.
                    extractDataSourceMetaInfo());
        } catch (DataSourceException e) {
            String errMsg = "Data source error occurred";
            throw new DataSourceException(errMsg, e);
        }
    }

    private boolean isAuthorized() throws UserStoreException {
        String loggeduser = CarbonContext.getThreadLocalCarbonContext().getUsername();
        String admin = CarbonContext.getThreadLocalCarbonContext().getUserRealm().getRealmConfiguration().getAdminUserName();

        if (loggeduser != null && loggeduser.equals(admin)) {
            return true;
        }
        log.error("Logged user '" + loggeduser + "', not the authorized admin user '" + admin + "'.");
        return false;
    }

    private File createConfigurationFile(String domainName) {
        String fileName = domainName.replace(".", "_");
        File userStoreConfigFile;
        int tenantId = CarbonContext.getThreadLocalCarbonContext().getTenantId();

        if (tenantId == MultitenantConstants.SUPER_TENANT_ID) {
            File userStore = new File(deploymentDirectory);
            if (!userStore.exists()) {
                if (new File(deploymentDirectory).mkdir()) {
                    //folder 'userstores' created
                    log.info("folder 'userstores' created to store configurations for super tenant");
                } else {
                    log.error("Error at creating 'userstores' directory to store configurations for super tenant");
                }
            }
            userStoreConfigFile = new File(deploymentDirectory + File.separator + fileName + ".xml");
        } else {
            String tenantFilePath = CarbonUtils.getCarbonTenantsDirPath();
            tenantFilePath = tenantFilePath + File.separator + tenantId + File.separator + USERSTORES;
            File userStore = new File(tenantFilePath);
            if (!userStore.exists()) {
                if (new File(tenantFilePath).mkdir()) {
                    //folder 'userstores' created
                    log.info("folder 'userstores' created to store configurations for tenant = " + tenantId);
                } else {
                    log.error("Error at creating 'userstores' directory to store configurations for tenant:" + tenantId);
                }
            }
            userStoreConfigFile = new File(tenantFilePath + File.separator + fileName + ".xml");
        }
        return userStoreConfigFile;
    }


    private void writeUserMgtXMLFile(File userStoreConfigFile, UserStoreDTO userStoreDTO,
                                     boolean editSecondaryUserStore) throws UserStoreException {
        StreamResult result = new StreamResult(userStoreConfigFile);
        DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();

        try {
            DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
            Document doc = docBuilder.newDocument();

            //create UserStoreManager element
            Element userStoreElement = doc.createElement(UserCoreConstants.RealmConfig.LOCAL_NAME_USER_STORE_MANAGER);
            doc.appendChild(userStoreElement);

            Attr attrClass = doc.createAttribute("class");
            attrClass.setValue(userStoreDTO.getClassName());
            userStoreElement.setAttributeNode(attrClass);

            addProperties(userStoreDTO.getClassName(), userStoreDTO.getProperties(), doc, userStoreElement,
                    editSecondaryUserStore);
            addProperty(UserStoreConfigConstants.DOMAIN_NAME, userStoreDTO.getDomainId(), doc, userStoreElement, false);
            addProperty(DESCRIPTION, userStoreDTO.getDescription(), doc, userStoreElement, false);
            DOMSource source = new DOMSource(doc);

            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.METHOD, "xml");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "6");
            transformer.transform(source, result);
        } catch (ParserConfigurationException e) {
            String errMsg = " Error occured due to serious parser configuration exception of " + userStoreConfigFile;
            throw new UserStoreException(errMsg, e);
        } catch (TransformerException e) {
            String errMsg = " Error occured during the transformation process of " + userStoreConfigFile;
            throw new UserStoreException(errMsg, e);
        }
    }

    /**
     * Obtains the mandatory properties for a given userStoreClass
     *
     * @param userStoreClass userStoreClass name
     * @return Property[] of Mandatory Properties
     */
    private Property[] getMandatoryProperties(String userStoreClass) {
        return UserStoreManagerRegistry.getUserStoreProperties(userStoreClass).getMandatoryProperties();
    }

    /**
     * Check whether the given property should be encrypted or not.
     *
     * @param mandatoryProperties mandatory property array
     * @param propertyName        property name
     * @return returns true if the property should be encrypted
     */
    private boolean isPropertyToBeEncrypted(Property[] mandatoryProperties,
                                            String propertyName) {
        for (Property property : mandatoryProperties) {
            if (propertyName.equalsIgnoreCase(property.getName())) {
                return property.getDescription().contains(UserStoreConfigurationConstant.ENCRYPT_TEXT);
            }
        }
        return false;
    }

    /**
     * Generate the RandomPassword[] from secondaryRealmConfiguration for given userStoreClass
     *
     * @param userStoreClass              Extract the mandatory properties of this class
     * @param randomPhrase                The randomly generated keyword which will be stored in
     *                                    RandomPassword object
     * @param secondaryRealmConfiguration RealmConfiguration object consists the properties
     * @return RandomPassword[] array for each property
     */
    private RandomPassword[] getRandomPasswordProperties(String userStoreClass,
                                                         String randomPhrase, RealmConfiguration secondaryRealmConfiguration) {
        //First check for mandatory field with #encrypt
        Property[] mandatoryProperties = getMandatoryProperties(userStoreClass);
        ArrayList<RandomPassword> randomPasswordArrayList = new ArrayList<RandomPassword>();
        for (Property property : mandatoryProperties) {
            String propertyName = property.getName();
            if (property.getDescription().contains(UserStoreConfigurationConstant.ENCRYPT_TEXT)) {
                RandomPassword randomPassword = new RandomPassword();
                randomPassword.setPropertyName(propertyName);
                randomPassword.setPassword(secondaryRealmConfiguration.getUserStoreProperty(propertyName));
                randomPassword.setRandomPhrase(randomPhrase);
                randomPasswordArrayList.add(randomPassword);
            }
        }
        return randomPasswordArrayList.toArray(new RandomPassword[randomPasswordArrayList.size()]);
    }

    /**
     * Create and update the RandomPasswordContainer with given unique ID and randomPasswords array
     *
     * @param randomPasswords array contains the elements to be encrypted with thier random
     *                        password phrase, password and unique id
     * @param uuid            Unique id of the RandomPasswordContainer
     */
    private void updatePasswordContainer(RandomPassword[] randomPasswords, String uuid) {

        if (randomPasswords != null) {
            if (log.isDebugEnabled()) {
                log.debug("updatePasswordContainer reached for number of random password properties length = " +
                        randomPasswords.length);
            }
            RandomPasswordContainer randomPasswordContainer = new RandomPasswordContainer();
            randomPasswordContainer.setRandomPasswords(randomPasswords);
            randomPasswordContainer.setUniqueID(uuid);

            RandomPasswordContainerCache.getInstance().getRandomPasswordContainerCache().put(uuid,
                    randomPasswordContainer);
        }
    }

    /**
     * Get the RandomPasswordContainer object from the cache for given unique id
     *
     * @param uniqueID Get and Remove the unique id for that particualr cache
     * @return RandomPasswordContainer of particular unique ID
     */
    private RandomPasswordContainer getAndRemoveRandomPasswordContainer(String uniqueID) {
        return RandomPasswordContainerCache.getInstance().getRandomPasswordContainerCache().getAndRemove(uniqueID);
    }

    /**
     * Obtain the UniqueID ID constant value from the propertyDTO object which was set well
     * before sending the edit request.
     *
     * @param propertyDTOs PropertyDTO[] object passed from JSP page
     * @return unique id string value
     */
    private String getUniqueIDFromUserDTO(PropertyDTO[] propertyDTOs) {

        int length = propertyDTOs.length;
        for (int i = length - 1; i >= 0; i--) {
            PropertyDTO propertyDTO = propertyDTOs[i];
            if (propertyDTO != null && propertyDTO.getName() != null && propertyDTO.getName()
                    .equalsIgnoreCase(UserStoreConfigurationConstant.UNIQUE_ID_CONSTANT)) {
                return propertyDTO.getValue();
            }
        }

        return null;
    }

    /**
     * Finds the RandomPassword object for a given propertyName in the RandomPasswordContainer
     * ( Which is unique per uniqueID )
     *
     * @param randomPasswordContainer RandomPasswordContainer object of an unique id
     * @param propertyName            RandomPassword object to be obtained for that property
     * @return Returns the RandomPassword object from the
     */
    private RandomPassword getRandomPassword(RandomPasswordContainer randomPasswordContainer,
                                             String propertyName) {

        RandomPassword[] randomPasswords = randomPasswordContainer.getRandomPasswords();

        if (randomPasswords != null) {
            for (RandomPassword randomPassword : randomPasswords) {
                if (randomPassword.getPropertyName().equalsIgnoreCase(propertyName)) {
                    return randomPassword;
                }
            }
        }
        return null;
    }
}
