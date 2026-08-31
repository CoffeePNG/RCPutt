package com.redcoffee.puttputt.loader;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.jetbrains.annotations.NotNull;

/**
 * Resolves the SQLite driver at load time.
 *
 * <p>{@code paper-plugin.yml} has no {@code libraries:} block - that was a legacy {@code plugin.yml}
 * feature - so the equivalent is a loader that hands Paper a Maven coordinate. The driver is still
 * never shaded: one copy, fetched once, living in the plugin's own classloader.
 */
@SuppressWarnings("UnstableApiUsage")
public final class RCPuttPuttLoader implements PluginLoader {

    private static final String SQLITE = "org.xerial:sqlite-jdbc:3.46.1.3";

    @Override
    public void classloader(@NotNull PluginClasspathBuilder classpathBuilder) {
        MavenLibraryResolver resolver = new MavenLibraryResolver();
        resolver.addRepository(new RemoteRepository.Builder(
                "central", "default", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR).build());
        resolver.addDependency(new Dependency(new DefaultArtifact(SQLITE), null));
        classpathBuilder.addLibrary(resolver);
    }
}
