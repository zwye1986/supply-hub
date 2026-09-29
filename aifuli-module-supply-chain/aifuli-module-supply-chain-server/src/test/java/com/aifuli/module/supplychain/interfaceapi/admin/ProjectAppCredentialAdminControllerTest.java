package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.module.supplychain.tradecore.buyer.application.ProjectAppCredentialAdminService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 项目发码管理入口契约；见 docs/superpowers/specs/2026-09-29-project-app-issuance-platform-signing-callback-design.md#3-项目应用管理。 */
class ProjectAppCredentialAdminControllerTest {

    @Test
    void oneTimeResponseDisablesCachingAndRequiresWritePermission() throws Exception {
        ProjectAppCredentialAdminService service = mock(ProjectAppCredentialAdminService.class);
        when(service.enable("PRJ_01", null)).thenReturn(
                new ProjectAppCredentialAdminService.IssuedCredential("AP_01", 1, "one-time-key", "one-****-key"));
        ProjectAppCredentialAdminController controller = new ProjectAppCredentialAdminController(service);

        var response = controller.enable("PRJ_01");

        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getBody().getData().appKey()).isEqualTo("one-time-key");
        assertThat(ProjectAppCredentialAdminController.class
                .getMethod("enable", String.class).getAnnotation(PreAuthorize.class)).isNotNull();
    }
}
