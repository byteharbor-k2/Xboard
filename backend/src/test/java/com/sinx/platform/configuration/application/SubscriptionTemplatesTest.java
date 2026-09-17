package com.sinx.platform.configuration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.sinx.platform.configuration.application.SubscriptionTemplates.Kind;
import com.sinx.platform.shared.web.ApiProblemException;

import tools.jackson.databind.ObjectMapper;

/**
 * The templates that ship in the jar, and what an administrator is allowed to
 * replace them with.
 */
class SubscriptionTemplatesTest {

    private final SubscriptionTemplates templates = new SubscriptionTemplates(
        new ObjectMapper()
    );

    @Test
    void everyTemplateShipsWithADefaultThatParses() {
        for (Kind kind : Kind.values()) {
            assertThat(templates.bundled(kind)).isNotBlank();
            assertThatCode(() -> templates.validate(kind, templates.bundled(kind)))
                .doesNotThrowAnyException();
        }
    }

    @Test
    void aBlankSettingMeansTheBundledTemplate() {
        assertThat(templates.effective(Kind.CLASH, null))
            .isEqualTo(templates.bundled(Kind.CLASH));
        assertThat(templates.effective(Kind.CLASH, "   \n "))
            .isEqualTo(templates.bundled(Kind.CLASH));
    }

    @Test
    void aStoredTemplateIsUsedAsItWasStored() {
        String stored = templates.bundled(Kind.SING_BOX);

        assertThat(templates.effective(Kind.SING_BOX, stored)).isEqualTo(stored);
    }

    @Test
    void aTemplateThatIsNotEvenParseableIsRefused() {
        assertThatThrownBy(() -> templates.validate(Kind.CLASH, "proxies: ["))
            .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> templates.validate(Kind.SING_BOX, "{ nope"))
            .isInstanceOf(ApiProblemException.class);
    }

    @Test
    void aTemplateTheRendererWouldWriteIntoMustHaveTheKeysItWritesInto() {
        // Both of these parse. They fail only at request time, for every user,
        // which is what makes checking for the keys here worth doing.
        assertThatThrownBy(() ->
            templates.validate(Kind.CLASH, "rules: []")
        ).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() ->
            templates.validate(Kind.SING_BOX, "{\"log\": {}}")
        ).isInstanceOf(ApiProblemException.class);
    }

    @Test
    void aScalarTemplateIsRefusedRatherThanTreatedAsAnEmptyConfig() {
        assertThatThrownBy(() -> templates.validate(Kind.CLASH, "just a string"))
            .isInstanceOf(ApiProblemException.class);
    }

    @Test
    void theSettingKeysAreTheOnesTheAdminSectionReadsAndWrites() {
        assertThat(Kind.CLASH.settingKey()).isEqualTo("subscribe_template_clash");
        assertThat(Kind.CLASH_META.settingKey())
            .isEqualTo("subscribe_template_clashmeta");
        assertThat(Kind.SING_BOX.settingKey())
            .isEqualTo("subscribe_template_singbox");
        assertThat(Kind.STASH.settingKey()).isEqualTo("subscribe_template_stash");
        assertThat(Kind.SURGE.settingKey()).isEqualTo("subscribe_template_surge");
        assertThat(Kind.SURFBOARD.settingKey())
            .isEqualTo("subscribe_template_surfboard");
        assertThat(Kind.bySettingKey("subscribe_template_clash"))
            .isEqualTo(Kind.CLASH);
        assertThat(Kind.bySettingKey("subscribe_template_surge")).isEqualTo(Kind.SURGE);
        // A key that names no template is not a template kind.
        assertThat(Kind.bySettingKey("subscribe_template_nonesuch")).isNull();
    }
}
