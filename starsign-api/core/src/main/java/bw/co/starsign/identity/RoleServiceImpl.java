// license-header java merge-point
/**
 * This is only generated once! It will never be overwritten.
 * You can (and have to!) safely modify it by hand.
 * TEMPLATE:    SpringServiceImpl.vsl in andromda-spring cartridge
 * MODEL CLASS: AndroMDAModel::backend::bw.co.starsign::identity::RoleService
 * STEREOTYPE:  Service
 */
package bw.co.starsign.identity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import bw.co.starsign.StarsignSpecifications;
import bw.co.starsign.audit.AuditTrailService;
import bw.co.starsign.organisation.Organisation;
import bw.co.starsign.organisation.OrganisationRepository;
import jakarta.validation.Valid;

/**
 * @see bw.co.starsign.identity.RoleService
 */
@Service("roleService")
@Transactional(propagation = Propagation.REQUIRED, readOnly = false)
public class RoleServiceImpl
    extends RoleServiceBase
{
    private final OrganisationRepository organisationRepository;

    public RoleServiceImpl(
        @Lazy KeycloakAdminService keycloakAdminService,
        @Lazy AuditTrailService auditTrailService,
        RoleRepository roleRepository,
        RoleMapper roleMapper,
        PermissionRepository permissionRepository,
        PermissionMapper permissionMapper,
        OrganisationRepository organisationRepository) {

        super(
            keycloakAdminService,
            auditTrailService,
            roleRepository,
            roleMapper,
            permissionRepository,
            permissionMapper);

        this.organisationRepository = organisationRepository;
    }

    /**
     * Built-in (global) roles plus the roles defined by the organisation.
     *
     * @see bw.co.starsign.identity.RoleService#findAll(String)
     */
    @Override
    @Transactional(readOnly = true)
    protected List<RoleDTO> handleFindAll(String organisationId)
        throws Exception
    {
        Specification<Role> global = StarsignSpecifications.findByAttributeNull("organisation");
        Specification<Role> organisation = StarsignSpecifications.findByAttributeEquals(UUID.fromString(organisationId), "organisation", "id");

        List<Role> roles = roleRepository.findAll(global.or(organisation), Sort.by(Direction.ASC, "name"));
        return roleMapper.toRoleDTOCollection(roles);
    }

    /**
     * @see bw.co.starsign.identity.RoleService#findById(String)
     */
    @Override
    @Transactional(readOnly = true)
    protected RoleDTO handleFindById(String id)
        throws Exception
    {
        return roleMapper.toRoleDTO(getRole(id));
    }

    /**
     * Creates or updates a custom role. Built-in roles are fixed: only their description may change.
     *
     * @see bw.co.starsign.identity.RoleService#save(RoleDTO)
     */
    @Override
    protected RoleDTO handleSave(@Valid RoleDTO role)
        throws Exception
    {
        Role entity;

        if (StringUtils.isBlank(role.getId())) {

            if (StringUtils.isBlank(role.getOrganisationId())) {
                throw new RoleServiceException("Custom roles must belong to an organisation.");
            }

            Organisation organisation = organisationRepository.findById(UUID.fromString(role.getOrganisationId()))
                .orElseThrow(() -> new RoleServiceException("Could not find organisation: " + role.getOrganisationId()));

            entity = roleMapper.roleDTOToEntity(role);
            entity.setId(null);
            entity.setOrganisation(organisation);
            entity.setBuiltIn(false);
            entity.setSystemRole(null);

            if (StringUtils.isBlank(entity.getKeycloakRoleName())) {
                entity.setKeycloakRoleName(entity.getCode());
            }

            keycloakAdminService.createRealmRole(entity.getKeycloakRoleName(), entity.getDescription());

        } else {

            entity = getRole(role.getId());

            if (entity.isBuiltIn()) {
                entity.setDescription(role.getDescription());
            } else {
                roleMapper.updateRoleFromRoleDTO(role, entity);
            }
        }

        if (role.getPermissions() != null && !entity.isBuiltIn()) {
            entity.setPermissions(findPermissions(role.getPermissions().stream().map(PermissionDTO::getCode).toList()));
        }

        entity = roleRepository.save(entity);
        return roleMapper.toRoleDTO(entity);
    }

    /**
     * Built-in roles cannot be removed.
     *
     * @see bw.co.starsign.identity.RoleService#remove(String)
     */
    @Override
    protected boolean handleRemove(String id)
        throws Exception
    {
        Role role = getRole(id);

        if (role.isBuiltIn()) {
            throw new RoleServiceException("Built-in roles cannot be removed.");
        }

        roleRepository.delete(role);
        return true;
    }

    /**
     * @see bw.co.starsign.identity.RoleService#getPermissions()
     */
    @Override
    @Transactional(readOnly = true)
    protected List<PermissionDTO> handleGetPermissions()
        throws Exception
    {
        List<Permission> permissions = permissionRepository.findAll(Sort.by(Direction.ASC, "module", "code"));
        return permissionMapper.toPermissionDTOCollection(permissions);
    }

    /**
     * @see bw.co.starsign.identity.RoleService#setRolePermissions(String, List<String>)
     */
    @Override
    protected RoleDTO handleSetRolePermissions(String id, List<String> permissionCodes)
        throws Exception
    {
        Role role = getRole(id);

        if (role.isBuiltIn()) {
            throw new RoleServiceException("The permissions of built-in roles cannot be changed.");
        }

        role.setPermissions(findPermissions(permissionCodes));
        role = roleRepository.save(role);
        return roleMapper.toRoleDTO(role);
    }

    private Role getRole(String id) {
        return roleRepository.findById(UUID.fromString(id))
            .orElseThrow(() -> new RoleServiceException("Could not find role: " + id));
    }

    private List<Permission> findPermissions(Collection<String> codes) {

        if (codes == null || codes.isEmpty()) {
            return new ArrayList<>();
        }

        List<Permission> permissions = permissionRepository.findAll(StarsignSpecifications.findByAttributeIn(codes, "code"));
        if (permissions.size() != codes.size()) {

            List<String> found = permissions.stream().map(Permission::getCode).toList();
            List<String> unknown = codes.stream().filter(code -> !found.contains(code)).toList();
            throw new RoleServiceException("Unknown permissions: " + unknown);
        }

        return new ArrayList<>(permissions);
    }
}
