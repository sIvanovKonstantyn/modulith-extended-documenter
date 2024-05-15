package org.svosh.open.documenter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.SpringBean;
import org.springframework.modulith.docs.Documenter;
import org.springframework.util.FileCopyUtils;

import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;

public class ExtendedDocumenter extends Documenter {

    private final String applicationName;

    private static final String APPLICATION_DOC_FILE = "application.adoc";
    private static final String CONFIGURATION_DOC_FILE = "configuration.adoc";
    private static final String DEFAULT_LOCATION = "spring-modulith-docs";
    private final ApplicationModules modules;

    public ExtendedDocumenter(String applicationName, ApplicationModules modules) {
        super(modules);
        this.applicationName = applicationName;
        this.modules = modules;
    }

    public ExtendedDocumenter writeDocumentation() {
        super.writeDocumentation();

        modules.forEach(this::writeModuleDocumentation);

        writeConfigurationDocumentation();

        writeApplicationReferenceDocumentation(modules);

        return this;
    }

    private void writeApplicationReferenceDocumentation(ApplicationModules modules) {
        var file = recreateFile(APPLICATION_DOC_FILE);

        try (Writer writer = new FileWriter(file.toFile())) {
            writer.write("== " + applicationName + "\n\n");
            writer.write("=== Reference documentation:\n\n");
            writer.write("xref:openapi.json#[Rest API]\n\n");
            writer.write("xref:components.puml#[Components]\n\n");
            addModulesReferences(modules, writer);
            writer.write("<<configuration.adoc#,Configuration>>\n\n");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void addModulesReferences(ApplicationModules modules, Writer writer) {
        modules.forEach(m -> {
            try {
                writer.write("<<" + "module-%s.adoc".formatted(m.getName()) + "#," + m.getDisplayName() + " Module>>\n\n");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void writeConfigurationDocumentation() {
        var stream = getClass().getClassLoader()
            .getResourceAsStream("application.properties");//TODO support other types

        if (stream == null) {
            return;
        }

        var file = recreateFile(CONFIGURATION_DOC_FILE);

        try (var writer = new FileWriter(file.toFile());
             var br = new BufferedReader(new InputStreamReader(stream))) {
            var documentationText = br.lines()
                .map(this::formatConfigurationRow)
                .collect(Collectors.joining("\n\n"));

            writer.write(documentationText);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String formatConfigurationRow(String s) {
        if (s.startsWith("#")) {
            return "_" + s.replace("#", "")
                .trim() + "_";
        } else {
            var parts = s.split("=", 2);
            if (parts.length < 2) {
                return s;
            }
            return "*" + parts[0] + "*\n\n'''";
        }
    }

    private void writeModuleDocumentation(ApplicationModule module) {
        module.getBasePackage()
            .getAnnotation(ExtendedDocumentation.class)
            .ifPresent(da -> {
                var documentationText = da.value();
                addTextToModuleDocumentaion(module, documentationText);
            });

        module.getSpringBeans()
            .forEach(b -> writeBeanDocumentation(b, module));
    }

    private void writeBeanDocumentation(SpringBean springBean, ApplicationModule module) {
        var springBeanType = springBean.getType();
        if (!springBeanType.isAnnotatedWith(ExtendedDocumentation.class)) {
            return;
        }

        var documentationText = springBeanType.getAnnotationOfType(ExtendedDocumentation.class)
            .value();

        addTextToModuleDocumentaion(module, documentationText);

        springBeanType.getMethods()
            .stream()
            .filter(m -> m.getModifiers()
                .contains(JavaModifier.PUBLIC))
            .forEach(m -> writePublicMethodDocumentation(m, module));
    }

    private void addTextToModuleDocumentaion(ApplicationModule module, String documentationText) {
        try (var writer = new FileWriter(getTargetFileName(module.getName()), true)) {

            writer.write(documentationText);

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void writePublicMethodDocumentation(JavaMethod method, ApplicationModule module) {
        if (!method.isAnnotatedWith(ExtendedDocumentation.class)) {
            return;
        }

        var documentationText = method.getAnnotationOfType(ExtendedDocumentation.class)
            .value();

        addTextToModuleDocumentaion(module, documentationText);
    }

    private String getTargetFileName(String moduleName) {
        var fileName = "module-%s.adoc".formatted(moduleName);

        return Paths.get(getDefaultOutputDirectory(), fileName)
            .toString();
    }

    private static String getDefaultOutputDirectory() {
        return getTargetFolder().concat("/")
            .concat(DEFAULT_LOCATION);
    }

    private static String getTargetFolder() {
        return new File("pom.xml").exists() ? "target" : "build";
    }

    private Path recreateFile(String name) {

        try {

            Files.createDirectories(Paths.get(getDefaultOutputDirectory()));
            var filePath = Paths.get(getDefaultOutputDirectory(), name);
            Files.deleteIfExists(filePath);

            return Files.createFile(filePath);

        } catch (IOException o_O) {
            throw new RuntimeException(o_O);
        }
    }

    public void moveToFolder(String destinationDir) {
        var sourceDir = getDefaultOutputDirectory();

        Stream.concat(Stream.of(Paths.get(sourceDir, "configuration.adoc"),
                Paths.get(sourceDir, "components.puml"),
                Paths.get(sourceDir, "application.adoc"),
                Paths.get(getTargetFolder(), "openapi.json")), modules.stream()
                .map(m -> Paths.get(getTargetFileName(m.getName()))))
            .map(Path::toFile)
            .forEach(file -> {
                try {
                    FileCopyUtils.copy(file, Paths.get(destinationDir, file.getName())
                        .toFile());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
    }
}
