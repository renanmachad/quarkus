package io.quarkus.devtools.commands.handlers;

import static io.quarkus.devtools.project.state.ProjectStates.resolveProjectState;
import static io.quarkus.devtools.project.update.ProjectUpdateInfos.resolvePlatformUpdateInfo;
import static io.quarkus.devtools.project.update.ProjectUpdateInfos.resolveRecommendedState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.devtools.commands.UpdateProject;
import io.quarkus.devtools.commands.data.QuarkusCommandException;
import io.quarkus.devtools.commands.data.QuarkusCommandInvocation;
import io.quarkus.devtools.commands.data.QuarkusCommandOutcome;
import io.quarkus.devtools.messagewriter.MessageWriter;
import io.quarkus.devtools.project.BuildTool;
import io.quarkus.devtools.project.QuarkusProject;
import io.quarkus.devtools.project.QuarkusProjectHelper;
import io.quarkus.devtools.project.state.ProjectState;
import io.quarkus.devtools.project.update.ExtensionUpdateInfo;
import io.quarkus.devtools.project.update.PlatformInfo;
import io.quarkus.devtools.project.update.ProjectExtensionsUpdateInfo;
import io.quarkus.devtools.project.update.ProjectPlatformUpdateInfo;
import io.quarkus.devtools.project.update.ProjectUpdateInfos;
import io.quarkus.devtools.project.update.rewrite.QuarkusUpdateCommand;
import io.quarkus.devtools.project.update.rewrite.QuarkusUpdateException;
import io.quarkus.devtools.project.update.rewrite.QuarkusUpdates;
import io.quarkus.devtools.project.update.rewrite.QuarkusUpdatesRepository;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.platform.tools.ToolsConstants;
import io.quarkus.registry.catalog.ExtensionCatalog;

public class UpdateProjectCommandHandler implements QuarkusCommandHandler {
    public static final String ADD = "Add:";
    public static final String REMOVE = "Remove:";
    public static final String UPDATE = "Update:";
    public static final String ITEM_FORMAT = "%-7s %s";

    @Override
    public QuarkusCommandOutcome execute(QuarkusCommandInvocation invocation) throws QuarkusCommandException {
        final ApplicationModel appModel = invocation.getValue(UpdateProject.APP_MODEL);
        final ExtensionCatalog targetCatalog = invocation.getValue(UpdateProject.TARGET_CATALOG);
        final String targetPlatformVersion = invocation.getValue(UpdateProject.TARGET_PLATFORM_VERSION);

        final boolean perModule = invocation.getValue(UpdateProject.PER_MODULE, false);
        final ProjectState currentState = resolveProjectState(appModel,
                invocation.getQuarkusProject().getExtensionsCatalog());
        final ArtifactCoords projectQuarkusPlatformBom = getProjectQuarkusPlatformBOM(currentState);
        if (projectQuarkusPlatformBom == null) {
            invocation.log().error("The project does not import any Quarkus platform BOM");
            return QuarkusCommandOutcome.failure();
        }
        if (Objects.equals(projectQuarkusPlatformBom.getVersion(), targetPlatformVersion)) {
            ProjectInfoCommandHandler.logState(currentState, perModule, true, invocation.getQuarkusProject().log());
        } else {
            invocation.log().info("Instructions to update this project from '%s' to '%s':",
                    projectQuarkusPlatformBom.getVersion(), targetPlatformVersion);
            final QuarkusProject quarkusProject = invocation.getQuarkusProject();
            final ProjectState recommendedState = resolveRecommendedState(currentState, targetCatalog, invocation.log());
            final ProjectPlatformUpdateInfo platformUpdateInfo = resolvePlatformUpdateInfo(currentState,
                    recommendedState);
            final ProjectExtensionsUpdateInfo extensionsUpdateInfo = ProjectUpdateInfos.resolveExtensionsUpdateInfo(
                    currentState,
                    recommendedState);

            logUpdates(currentState, recommendedState, platformUpdateInfo, extensionsUpdateInfo, false, perModule,
                    quarkusProject.log());
            final boolean noRewrite = invocation.getValue(UpdateProject.NO_REWRITE, false);

            if (!noRewrite) {
                final BuildTool buildTool = quarkusProject.getExtensionManager().getBuildTool();
                String kotlinVersion = getMetadata(targetCatalog, "project", "properties", "kotlin-version");

                QuarkusUpdates.ProjectUpdateRequest request = new QuarkusUpdates.ProjectUpdateRequest(
                        buildTool,
                        projectQuarkusPlatformBom.getVersion(),
                        targetPlatformVersion,
                        kotlinVersion);
                Path recipe = null;
                try {
                    recipe = Files.createTempFile("quarkus-project-recipe-", ".yaml");
                    final String updateRecipesVersion = invocation.getValue(UpdateProject.REWRITE_UPDATE_RECIPES_VERSION,
                            QuarkusUpdatesRepository.DEFAULT_UPDATE_RECIPES_VERSION);
                    final QuarkusUpdatesRepository.FetchResult fetchResult = QuarkusUpdates.createRecipe(invocation.log(),
                            recipe,
                            QuarkusProjectHelper.artifactResolver(), buildTool, updateRecipesVersion, request);
                    final String rewritePluginVersion = invocation.getValue(UpdateProject.REWRITE_PLUGIN_VERSION,
                            fetchResult.getRewritePluginVersion());
                    final boolean rewriteDryRun = invocation.getValue(UpdateProject.REWRITE_DRY_RUN, false);
                    invocation.log().warn(
                            "The update feature does not yet handle updates of the extension versions. If needed, update your extensions manually.");
                    QuarkusUpdateCommand.handle(
                            invocation.log(),
                            buildTool,
                            quarkusProject.getProjectDirPath(),
                            rewritePluginVersion,
                            fetchResult.getRecipesGAV(),
                            recipe,
                            rewriteDryRun);
                } catch (IOException e) {
                    throw new QuarkusCommandException("Error while generating the project update script", e);
                } catch (QuarkusUpdateException e) {
                    throw new QuarkusCommandException("Error while running the project update script", e);
                } finally {
                    if (recipe != null) {
                        try {
                            Files.deleteIfExists(recipe);
                        } catch (IOException e) {
                            // ignore
                        }
                    }

                }
            }
        }
        return QuarkusCommandOutcome.success();
    }

    private static ArtifactCoords getProjectQuarkusPlatformBOM(ProjectState currentState) {
        for (ArtifactCoords c : currentState.getPlatformBoms()) {
            if (c.getArtifactId().equals(ToolsConstants.DEFAULT_PLATFORM_BOM_ARTIFACT_ID)) {
                return c;
            }
        }
        return null;
    }

    private static void logUpdates(ProjectState currentState, ProjectState recommendedState,
            ProjectPlatformUpdateInfo platformUpdateInfo,
            ProjectExtensionsUpdateInfo extensionsUpdateInfo, boolean recommendState,
            boolean perModule, MessageWriter log) {
        if (currentState.getPlatformBoms().isEmpty()) {
            log.info("The project does not import any Quarkus platform BOM");
            return;
        }
        if (currentState.getExtensions().isEmpty()) {
            log.info("Quarkus extension were not found among the project dependencies");
            return;
        }
        if (currentState == recommendedState) {
            log.info("The project is up-to-date");
            return;
        }

        if (recommendState) {
            ProjectInfoCommandHandler.logState(recommendedState, perModule, false, log);
            return;
        }

        if (platformUpdateInfo.isPlatformUpdatesAvailable()) {
            log.info("Recommended Quarkus platform BOM updates:");
            if (!platformUpdateInfo.getImportVersionUpdates().isEmpty()) {
                for (PlatformInfo importInfo : platformUpdateInfo.getImportVersionUpdates()) {
                    log.info(String.format(UpdateProjectCommandHandler.ITEM_FORMAT,
                            UpdateProjectCommandHandler.UPDATE, importInfo.getImported().toCompactCoords()) + " -> "
                            + importInfo.getRecommendedVersion());
                }
            }
            if (!platformUpdateInfo.getNewImports().isEmpty()) {
                for (PlatformInfo importInfo : platformUpdateInfo.getNewImports()) {
                    log.info(String.format(UpdateProjectCommandHandler.ITEM_FORMAT,
                            UpdateProjectCommandHandler.ADD, importInfo.getRecommended().toCompactCoords()));
                }
            }
            if (platformUpdateInfo.isImportsToBeRemoved()) {
                for (PlatformInfo importInfo : platformUpdateInfo.getPlatformImports().values()) {
                    if (importInfo.getRecommended() == null) {
                        log.info(String.format(UpdateProjectCommandHandler.ITEM_FORMAT,
                                UpdateProjectCommandHandler.REMOVE, importInfo.getImported().toCompactCoords()));
                    }
                }
            }
            log.info("");
        }

        if (extensionsUpdateInfo.isEmpty()) {
            if (!platformUpdateInfo.isPlatformUpdatesAvailable()) {
                log.info("The project is up-to-date");
            }
            return;
        }

        for (PlatformInfo platform : platformUpdateInfo.getPlatformImports().values()) {
            final String provider = platform.getRecommendedProviderKey();
            if (!extensionsUpdateInfo.getVersionedManagedExtensions().containsKey(provider)
                    && !extensionsUpdateInfo.getRemovedExtensions().containsKey(provider)
                    && !extensionsUpdateInfo.getAddedExtensions().containsKey(provider)) {
                continue;
            }
            log.info("Extensions from " + platform.getRecommendedProviderKey() + ":");
            for (ExtensionUpdateInfo e : extensionsUpdateInfo.getVersionedManagedExtensions().getOrDefault(provider,
                    Collections.emptyList())) {
                final StringBuilder sb = new StringBuilder();
                sb.append(String.format(UpdateProjectCommandHandler.ITEM_FORMAT,
                        UpdateProjectCommandHandler.UPDATE, e.getCurrentDep().getArtifact().toCompactCoords()));
                sb.append(" -> remove version (managed)");
                log.info(sb.toString());
            }
            for (ArtifactCoords e : extensionsUpdateInfo.getAddedExtensions().getOrDefault(provider, Collections.emptyList())) {
                log.info(String.format(UpdateProjectCommandHandler.ITEM_FORMAT, UpdateProjectCommandHandler.ADD,
                        e.getKey().toGacString()));
            }
            for (ArtifactCoords e : extensionsUpdateInfo.getRemovedExtensions().getOrDefault(provider,
                    Collections.emptyList())) {
                log.info(String.format(UpdateProjectCommandHandler.ITEM_FORMAT, UpdateProjectCommandHandler.REMOVE,
                        e.getKey().toGacString()));
            }
            log.info("");
        }

        if (!extensionsUpdateInfo.getNonPlatformExtensions().isEmpty()) {
            for (Map.Entry<String, List<ExtensionUpdateInfo>> provider : extensionsUpdateInfo.getNonPlatformExtensions()
                    .entrySet()) {
                log.info("Extensions from " + provider.getKey() + ":");
                for (ExtensionUpdateInfo info : provider.getValue()) {
                    if (info.getCurrentDep().isPlatformExtension()) {
                        log.info(String.format(UpdateProjectCommandHandler.ITEM_FORMAT,
                                UpdateProjectCommandHandler.ADD,
                                info.getRecommendedDependency().getArtifact().toCompactCoords()));
                    } else if (info.getRecommendedDependency().isPlatformExtension()) {
                        log.info(String.format(UpdateProjectCommandHandler.ITEM_FORMAT,
                                UpdateProjectCommandHandler.REMOVE, info.getCurrentDep().getArtifact().toCompactCoords()));
                    } else {
                        log.info(String.format(UpdateProjectCommandHandler.ITEM_FORMAT,
                                UpdateProjectCommandHandler.UPDATE,
                                info.getCurrentDep().getArtifact().toCompactCoords() + " -> "
                                        + info.getRecommendedDependency().getVersion()));
                    }
                }
                log.info("");
            }
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private <T> T getMetadata(ExtensionCatalog catalog, String... path) {
        Object currentValue = catalog.getMetadata();
        for (String pathElement : path) {
            if (!(currentValue instanceof Map)) {
                return null;
            }

            currentValue = ((Map) currentValue).get(pathElement);
        }

        return (T) currentValue;
    }

}
