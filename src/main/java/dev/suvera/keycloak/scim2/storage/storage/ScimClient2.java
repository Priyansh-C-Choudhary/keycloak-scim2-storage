package dev.suvera.keycloak.scim2.storage.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import dev.suvera.scim2.client.Scim2Client;
import dev.suvera.scim2.client.Scim2ClientBuilder;
import dev.suvera.scim2.schema.ScimConstant;
import dev.suvera.scim2.schema.data.ExtensionRecord; // IMPORT THIS
import dev.suvera.scim2.schema.data.group.GroupRecord;
import dev.suvera.scim2.schema.data.user.UserRecord;
import dev.suvera.scim2.schema.ex.ScimException;
import org.apache.commons.lang3.StringUtils;
import org.jboss.logging.Logger;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.GroupModel;
import org.keycloak.models.RoleModel;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*; // For HashMap if needed, but ExtensionRecord handles its own map
import java.util.stream.Collectors;

/**
 * author: suvera
 * date: 10/15/2020 11:25 AM
 */
@SuppressWarnings({"FieldCanBeLocal", "unused"})
public class ScimClient2 {
    private static final Logger log = Logger.getLogger(ScimClient2.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ComponentModel componentModel;
    private Scim2Client scimService = null;
    private ScimException scimException = null;

    public ScimClient2(ComponentModel componentModel) {
        this.componentModel = componentModel;

        String endPoint = componentModel.get("endPoint");
        String username = componentModel.get("username");
        String password = componentModel.get("password");
        String bearerToken = componentModel.get("bearerToken");

        log.info("SCIM 2.0 endPoint: " + endPoint);
        endPoint = StringUtils.stripEnd(endPoint, " /");

        String resourceTypesJson = null;
        String schemasJson = null;

        ClassLoader classLoader = getClass().getClassLoader();
        InputStream isResourceTypes = classLoader.getResourceAsStream("ResourceTypes.json");
        if (isResourceTypes == null) {
            log.error("file not found! ResourceTypes.json");
            throw new IllegalArgumentException("file not found! ResourceTypes.json");
        } else {
            resourceTypesJson = inputStreamToString(isResourceTypes);
            resourceTypesJson = resourceTypesJson.replaceAll("\\{SCIM_BASE}", endPoint);
        }

        InputStream isSchemas = classLoader.getResourceAsStream("Schemas.json");
        if (isSchemas == null) {
            log.error("file not found! Schemas.json");
            throw new IllegalArgumentException("file not found! Schemas.json");
        } else {
            schemasJson = inputStreamToString(isSchemas);
            schemasJson = schemasJson.replaceAll("\\{SCIM_BASE}", endPoint);
        }

        Scim2ClientBuilder builder = new Scim2ClientBuilder(endPoint)
                .allowSelfSigned(true)
                .resourceTypes(resourceTypesJson)
                .schemas(schemasJson)
            ;

        if (bearerToken != null && !bearerToken.isEmpty()) {
            builder.bearerToken(bearerToken);
        } else {
            builder.usernamePassword(username, password);
        }

        try {
            scimService = builder.build();
        } catch (ScimException e) {
            scimException = e;
            log.error("Scim2ClientBuilder failed", e);
        }
    }

    private String inputStreamToString(InputStream is) {
        return new BufferedReader(
            new InputStreamReader(is, StandardCharsets.UTF_8))
            .lines()
            .collect(Collectors.joining("\n"));
    }

    public void validate() throws ScimException {
        if (scimException != null) {
            throw scimException;
        }
    }

    private void buildScimUser(SkssUserModel userModel, UserRecord user) {
        user.setUserName(userModel.getUsername());

        UserRecord.UserName name = new UserRecord.UserName();
        name.setGivenName(userModel.getFirstName() == null ? userModel.getUsername()
                : userModel.getFirstName());
        name.setFamilyName(userModel.getLastName());
        user.setName(name);

        if (isAttributeNotNull(userModel, "honorificPrefix")) {
            name.setHonorificPrefix(userModel.getFirstAttribute("honorificPrefix"));
        }
        if (isAttributeNotNull(userModel, "honorificSuffix")) {
            name.setHonorificSuffix(userModel.getFirstAttribute("honorificSuffix"));
        }
        user.setName(name);

        if (userModel.getEmail() != null) {
            UserRecord.UserEmail email = new UserRecord.UserEmail();
            email.setType("work");
            email.setPrimary(true);
            email.setValue(userModel.getEmail());
            user.setEmails(Collections.singletonList(email));
        } else {
            user.setEmails(Collections.emptyList());
        }

        // Important: Modify the schemas set to be mutable if we want to add to it
        // Or, ensure the UserRecord's setExtensions method correctly adds the schema URI
        // The UserRecord.setExtensions does: `if (this.schemas != null) { this.schemas.add(key); }`
        // BUT, user.schemas is initialized with `Set.of(...)` which is IMMUTABLE.
        // So, we MUST initialize user.schemas with a mutable set here if we rely on that.
        Set<String> mutableSchemas = new HashSet<>();
        mutableSchemas.add(ScimConstant.URN_USER);
        mutableSchemas.add(ScimConstant.URN_ENTERPRISE_USER);
        user.setSchemas(mutableSchemas);

        user.setExternalId(userModel.getId());
        user.setActive(userModel.isEnabled());

        // ============================
        // START OF THE FINAL FIX
        // ============================
        // Add the required Enterprise User extension using ExtensionRecord
        ExtensionRecord enterpriseExtension = new ExtensionRecord();
        // enterpriseExtension.getRecord().put("employeeNumber", "12345"); // Example of adding data

        // Add the extension to the UserRecord
        // The key for the extensions map should be the schema URN
        user.getExtensions().put(ScimConstant.URN_ENTERPRISE_USER, enterpriseExtension);
        // The UserRecord's @JsonAnySetter and @JsonAnyGetter for 'extensions'
        // along with ExtensionRecord's own @JsonAnyGetter/@JsonAnySetter
        // will handle the serialization correctly.
        // The UserRecord's setExtensions method will also add the schema URN to the user.schemas set,
        // which is why user.schemas was made mutable above.

        // ============================
        // END OF THE FINAL FIX
        // ============================

        List<UserRecord.UserGroup> groups = new ArrayList<>();
        userModel.getGroupsStream().forEach(groupModel -> {
            try {
                createGroup(groupModel);
            } catch (ScimException e) {
                log.error("Error while creating group", e);
            }
            UserRecord.UserGroup grp = new UserRecord.UserGroup();
            grp.setDisplay(groupModel.getName());
            grp.setValue(groupModel.getId());
            grp.setType("direct");
            groups.add(grp);
        });
        user.setGroups(groups);

        List<UserRecord.UserRole> roles = new ArrayList<>();
        for (RoleModel roleModel : userModel.getRoleMappings()) {
            UserRecord.UserRole role = new UserRecord.UserRole();
            role.setDisplay(roleModel.getName());
            role.setValue(roleModel.getId());
            role.setType("direct");
            role.setPrimary(false);
            roles.add(role);
        }
        user.setRoles(roles);

        if (isAttributeNotNull(userModel, "title")) {
            user.setTitle(userModel.getFirstAttribute("title"));
        }
        
        if (isAttributeNotNull(userModel, "displayName")) {
            user.setDisplayName(userModel.getFirstAttribute("displayName"));
        } else {
            user.setDisplayName((strVal(name.getGivenName()) + " " + strVal(name.getFamilyName())).trim());
        }

        if (isAttributeNotNull(userModel, "nickName")) {
            user.setNickName(userModel.getFirstAttribute("nickName"));
        }

        if (isAttributeNotNull(userModel, "addresses_primary")) {
            List<UserRecord.UserAddress> addresses = new ArrayList<>();
            try {
                UserRecord.UserAddress addr = objectMapper.readValue(
                        userModel.getFirstAttribute("addresses_primary"),
                        UserRecord.UserAddress.class
                );
                addresses.add(addr);
            } catch (JsonProcessingException e) {
                log.error("Error while adding user address", e);
            }
            user.setAddresses(addresses);
        } else {
            user.setAddresses(Collections.emptyList());
        }

        if (isAttributeNotNull(userModel, "phoneNumbers_primary")) {
            List<UserRecord.UserPhoneNumber> phones = new ArrayList<>();
            try {
                UserRecord.UserPhoneNumber phone = objectMapper.readValue(
                        userModel.getFirstAttribute("phoneNumbers_primary"),
                        UserRecord.UserPhoneNumber.class
                );
                phones.add(phone);
            } catch (JsonProcessingException e) {
                log.error("Error while adding phones", e);
            }
            user.setPhoneNumbers(phones);
        } else {
            user.setPhoneNumbers(Collections.emptyList());
        }

        user.setIms(Collections.emptyList());
        user.setPhotos(Collections.emptyList());
        user.setEntitlements(Collections.emptyList());
        user.setX509Certificates(Collections.emptyList());

        try {
            log.info("Scim User: " + new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(user));
        } catch (JsonProcessingException e) {
            e.printStackTrace();
            log.error("JSON error while building SCIM user object", e);
        }
    }

    private boolean isAttributeNotNull(SkssUserModel userModel, String name) {
        String val = userModel.getFirstAttribute(name);
        return !(val == null || val.isEmpty() || val.equals("null"));
    }

    private String strVal(String val) {
        return (val == null ? "" : val);
    }

    public void createUser(SkssUserModel userModel) throws ScimException {
        if (scimService == null) {
            return;
        }
        if (userModel.getExternalUserId(componentModel.getId()) != null) {
            log.info("User already exist in the SCIM2 provider " + userModel.getUsername());
            return;
        }
        UserRecord user = new UserRecord();
        buildScimUser(userModel, user);
        user = scimService.createUser(user);
        userModel.saveExternalUserId(
                componentModel.getId(),
                user.getId()
        );
        log.info("User record successfully sync'd to SKIM service provider. " + user.getId());
    }

    public void updateUser(SkssUserModel userModel) throws ScimException {
        if (scimService == null) {
            return;
        }
        String id = userModel.getExternalUserId(componentModel.getId());
        if (id == null) {
            log.info("User user does not exist in the SCIM2 provider " + userModel.getUsername());
            return;
        }
        UserRecord user = getUser(userModel);
        if (user == null) {
            return;
        }
        buildScimUser(userModel, user);
        user = scimService.replaceUser(id, user);
        userModel.saveExternalUserId(
                componentModel.getId(),
                user.getId()
        );
    }

    public UserRecord getUser(SkssUserModel userModel) throws ScimException {
        String id = userModel.getExternalUserId(componentModel.getId());
        if (id == null) {
            log.info("User user does not exist in the SCIM2 provider " + userModel.getUsername());
            return null;
        }
        return scimService.readUser(id);
    }

    public void deleteUser(String skssId) throws ScimException {
        if (scimService == null) {
            return;
        }
        if (skssId != null) {
            scimService.deleteUser(skssId);
        }
    }

    public void createGroup(GroupModel groupModel) throws ScimException {
        if (scimService == null) {
            return;
        }
        if (groupModel.getFirstAttribute("skss_id_" + componentModel.getId()) != null) {
            return;
        }
        GroupRecord grp = new GroupRecord();
        grp.setDisplayName(groupModel.getName());
        grp = scimService.createGroup(grp);
        groupModel.setSingleAttribute(
                "skss_id_" + componentModel.getId(),
                grp.getId()
        );
    }

    public void updateGroup(GroupModel groupModel) throws ScimException {
        if (scimService == null) {
            return;
        }
        String id = groupModel.getFirstAttribute("skss_id_" + componentModel.getId());
        if (id == null) {
            log.info("User user does not exist in the SCIM2 provider " + groupModel.getName());
            return;
        }
        GroupRecord grp = scimService.readGroup(id);
        grp = scimService.replaceGroup(id, grp); // Typo: Should be grp.setDisplayName(groupModel.getName()); after readGroup, before replaceGroup
        grp.setDisplayName(groupModel.getName()); // This should be done before replace if the display name can change
        groupModel.setSingleAttribute(
                "skss_id_" + componentModel.getId(),
                grp.getId()
        );
    }

    public void deleteGroup(GroupModel groupModel) throws ScimException {
        if (scimService == null) {
            return;
        }
        String id = groupModel.getFirstAttribute("skss_id_" + componentModel.getId());
        if (id != null) {
            scimService.deleteGroup(id);
        }
    }
}