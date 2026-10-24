package dev.vexelray.trace.weave;

import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * Weave the module's compiled classes: every {@code @Trace} method writes its row, and the module's sites are
 * listed in a generated {@code TraceSites}. Runs after {@code javac}, at {@code process-classes}. See
 * {@code docs/trace.md}.
 *
 * <pre>{@code
 * <plugin>
 *   <groupId>dev.vexelray</groupId>
 *   <artifactId>vexelray-trace-maven-plugin</artifactId>
 *   <executions><execution><goals><goal>weave</goal></goals></execution></executions>
 * </plugin>
 * }</pre>
 */
@Mojo(name = "weave", defaultPhase = LifecyclePhase.PROCESS_CLASSES, threadSafe = true,
        requiresDependencyResolution = ResolutionScope.COMPILE)
public final class WeaveMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** The classes to weave: the module's own, in place. */
    @Parameter(defaultValue = "${project.build.outputDirectory}", required = true)
    private File classes;

    /** The module's name, which names its generated table class. */
    @Parameter(defaultValue = "${project.artifactId}", required = true)
    private String module;

    /** Leave the classes as {@code javac} wrote them. */
    @Parameter(property = "trace.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip || !classes.isDirectory()) {
            return;
        }
        try (URLClassLoader loader = new URLClassLoader(classPath(), getClass().getClassLoader())) {
            Weaver.Result r = Weaver.weave(classes.toPath(), module, loader);
            if (!r.sites().isEmpty()) {
                getLog().info("trace: " + r.sites().size() + " site" + (r.sites().size() == 1 ? "" : "s") + ", "
                        + r.woven() + " woven now, table " + r.sitesClass());
            }
        } catch (Weaver.WeaveException e) {
            throw new MojoFailureException(e.getMessage(), e);
        } catch (IOException | DependencyResolutionRequiredException e) {
            throw new MojoExecutionException("trace: could not weave " + classes + ": " + e.getMessage(), e);
        }
    }

    private URL[] classPath() throws DependencyResolutionRequiredException, MalformedURLException {
        List<URL> urls = new ArrayList<>();
        urls.add(classes.toURI().toURL());
        for (String element : project.getCompileClasspathElements()) {
            urls.add(new File(element).toURI().toURL());
        }
        return urls.toArray(URL[]::new);
    }
}
