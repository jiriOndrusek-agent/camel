/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.dsl.jbang.core.commands;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.apache.camel.dsl.jbang.core.common.CommandLineTestHelper;
import org.apache.camel.dsl.jbang.run.common.PluginExporter;
import org.apache.camel.dsl.jbang.run.common.Printer;
import org.apache.camel.dsl.jbang.run.common.RuntimeType;
import org.apache.camel.dsl.jbang.run.export.ExportContext;
import org.apache.maven.model.Model;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An export plugin can remove dependencies, also those added by another plugin (CAMEL-27546).
 */
class ExportPluginRemoveDependenciesTest extends ExportBaseTestSupport {

    @Test
    void pluginRemovesDependencyAddedByAnotherPlugin() throws Exception {
        PluginExporter adder = new TestExporter() {
            @Override
            public Set<String> getDependencies(RuntimeType runtimeType) {
                // mvn:g:a:v, camel:component and plain g:a (no version) notations
                return Set.of("mvn:org.acme:acme-model:1.0.0", "mvn:org.acme:acme-keep:1.0.0",
                        "camel:xslt", "camel:jsonpath", "org.acme:acme-plain");
            }
        };
        PluginExporter remover = new TestExporter() {
            @Override
            public Set<String> getDependenciesToRemove(RuntimeType runtimeType) {
                // the second one is not present, and is ignored
                return Set.of("org.acme:acme-model", "org.apache.camel:camel-xslt", "org.acme:acme-plain",
                        "org.acme:acme-absent");
            }
        };

        Export command = new CamelJBangMain().command(new PluginsExport(List.of(adder, remover)));
        CommandLineTestHelper.parse(command, "--gav=examples:route:1.0.0", "--dir=" + workingDir, "--quiet",
                "--runtime=" + RuntimeType.quarkus.runtime(), CamelCommandBaseTestSupport.quarkusExtRegistry());
        command.files = List.of("classpath:route.yaml");

        assertThat(command.doCall()).isZero();

        Model model = readMavenModel();
        assertThat(model.getDependencies())
                .extracting(d -> d.getGroupId() + ":" + d.getArtifactId())
                .contains("org.acme:acme-keep", "org.apache.camel.quarkus:camel-quarkus-jsonpath",
                        "org.apache.camel.quarkus:camel-quarkus-timer")
                .doesNotContain("org.acme:acme-model", "org.apache.camel.quarkus:camel-quarkus-xslt",
                        "org.acme:acme-plain", "org.acme:acme-absent");
    }

    private static class PluginsExport extends Export {
        private final List<PluginExporter> exporters;

        PluginsExport(List<PluginExporter> exporters) {
            this.exporters = exporters;
        }

        @Override
        protected ExportContext exportContext() {
            return super.exportContext()
                    .withPlugins(repos -> new ExportContext.ActivePlugins(List.of(), exporters));
        }
    }

    private abstract static class TestExporter implements PluginExporter {
        @Override
        public void addSourceFiles(Path buildDir, String packageName, Printer printer) {
            // noop
        }
    }
}
