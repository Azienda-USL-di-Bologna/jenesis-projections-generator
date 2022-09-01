package it.bologna.ausl.jenesisprojections.generator;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.annotation.processing.Processor;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.apache.maven.artifact.DependencyResolutionRequiredException;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.StringUtils;

/**
 * @requiresDependencyResolution compile
 * @goal generateProjectionsSource
 * @phase generate-sources
 * @description generate the projections java source code
 */
public class JenesisMojo extends AbstractMojo {

    /**
     * @parameter expression="${project}"
     * @required
     */
    protected MavenProject project;

    /**
     * The plugin descriptor
     *
     * @parameter default-value="${descriptor}"
     */
    protected PluginDescriptor descriptor;

    /**
     * @parameter
     */
    protected File outputJavaDirectory;

    /**
     * @parameter
     */
    protected String title;

    /**
     * @parameter
     */
    protected String relativeTargetPackage;

    /**
     * @parameter
     */
    protected String entitiesPackageStartNode;

    @SuppressWarnings("unchecked")
    /**
     * Filtra le directory con i sorgenti (le entità): tiene solo le directory eistenti; toglie anche la direcotry di output.
     */
    protected Set<File> getSourceDirectories() {
        File outputDirectory = this.outputJavaDirectory.getAbsoluteFile();
        String outputPath = outputDirectory.getAbsolutePath();
        Set<File> directories = new HashSet<>();        
        List<String> directoryNames = project.getCompileSourceRoots();
        for (String name : directoryNames) {
            File file = new File(name);
            if (!file.getAbsolutePath().equals(outputPath) && file.exists() && file.isDirectory()) {
                directories.add(file);    
            }            
        }
        return directories;
    }
    
    protected Set<File> getSourceFile(Set<File> directories) {
        Set<File> res = new HashSet<>();
        for (File dir : directories) {
            if (dir.isDirectory())
                res.addAll(getFiles(dir));
            else {
                String nome = dir.getName();
                if (nome.endsWith(".java")) {
                    res.add(dir);
                }
            }
                
        }
        return res;
    }
    
    private Set<File> getFiles(File file) {
        Set<File> res = new HashSet<>();
        if (file.isDirectory()) {
           for(File childFile: file.listFiles()) {
               res.addAll(getFiles(childFile));
           }
        }
        else {
            String nome = file.getName();
            if (nome.endsWith(".java")) {
                res.add(file);
            }
        }
        return res;
    }

    private List<String> buildCompilerOptions(String processor, String compileClassPath,
                                              String outputDirectory) throws IOException {
        Map<String, String> compilerOpts = new LinkedHashMap<>();

        // Default options
        compilerOpts.put("cp", compileClassPath);

        compilerOpts.put("proc:only", null);

        if (outputDirectory != null) {
            compilerOpts.put("s", outputDirectory);
        }

        StringBuilder builder = new StringBuilder();
        for (File file : getSourceDirectories()) {
            if (builder.length() > 0) {
                builder.append(";");
            }
            builder.append(file.getCanonicalPath());
        }
        compilerOpts.put("sourcepath", builder.toString());

        List<String> opts = new ArrayList<String>(compilerOpts.size() * 2);

        for (Map.Entry<String, String> compilerOption : compilerOpts.entrySet()) {
            opts.add("-" + compilerOption.getKey());
            String value = compilerOption.getValue();
            if (StringUtils.isNotBlank(value)) {
                opts.add(value);
            }
        }
        return opts;
    }

    private String buildCompileClasspath() throws DependencyResolutionRequiredException {
        List<String> pathElements = project.getCompileClasspathElements();

        if (pathElements == null || pathElements.isEmpty()) {
            return null;
        }

        StringBuilder result = new StringBuilder();
        int i;
        for (i = 0; i < pathElements.size() - 1; ++i) {
            result.append(pathElements.get(i)).append(File.pathSeparatorChar);
        }
        result.append(pathElements.get(i));
        return result.toString();
    }
    
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
                Set<File> sourceDirectories = getSourceDirectories();

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new MojoExecutionException("You need to run build with JDK or have tools.jar on the classpath."
                    + "If this occures during eclipse build make sure you run eclipse under JDK as well");
        }
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
        Iterable<? extends JavaFileObject> compilationUnits1 = fileManager.getJavaFileObjectsFromFiles(getSourceFile(sourceDirectories));

        String compileClassPath;
        try {
            compileClassPath = buildCompileClasspath();
        } catch (DependencyResolutionRequiredException ex) {
            throw new MojoExecutionException("errore nel reperimento del compileClassPath", ex);
        }


        if (outputJavaDirectory == null) {
            this.outputJavaDirectory = new File(project.getBuild().getSourceDirectory());
        }

//            File tempDirectory = null;
//
//                tempDirectory = new File(project.getBuild().getDirectory(), "apt"+System.currentTimeMillis());
//                tempDirectory.mkdirs();
//                outputDirectory = tempDirectory.getAbsolutePath();
//            

            List<String> compilerOptions;
            try {
                compilerOptions = buildCompilerOptions(null, compileClassPath, this.outputJavaDirectory.getAbsolutePath());
            } catch (IOException ex) {
                throw new MojoExecutionException("errore nella creazione delle opzioni di compilazione", ex);
            }

            Writer out = new StringWriter();
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                DiagnosticCollector<JavaFileObject> diagnosticCollector = new DiagnosticCollector<JavaFileObject>();
                JavaCompiler.CompilationTask task = compiler.getTask(out, fileManager, diagnosticCollector, compilerOptions, null, compilationUnits1);
                ArrayList<Processor> processors = new ArrayList<>();
                processors.add(new ProjectionProcessor(this.outputJavaDirectory, this.relativeTargetPackage));
                task.setProcessors(processors);
                Future<Boolean> future = executor.submit(task);
                Boolean rv = future.get();
//                List<Diagnostic<? extends JavaFileObject>> diagnostics = diagnosticCollector.getDiagnostics();
                if (Boolean.FALSE.equals(rv)) {
                    System.err.println("l'esezione del processor non è andata a buon fine:");
                    System.err.println(out.toString());
                    getLog().error(out.toString());
                }
//                processDiagnostics(diagnosticCollector.getDiagnostics());
            } catch (InterruptedException | ExecutionException ex) {
                throw new MojoExecutionException("errore durante l'esezione del Processor", ex);
            } finally {
                executor.shutdown();
            }
    }
}
