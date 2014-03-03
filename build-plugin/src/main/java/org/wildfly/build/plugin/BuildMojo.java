/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.wildfly.build.plugin;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.jboss.modules.ModuleIdentifier;
import org.wildfly.build.plugin.model.Build;
import org.wildfly.build.plugin.model.BuildModelParser;
import org.wildfly.build.plugin.model.CopyArtifact;
import org.wildfly.build.plugin.model.Server;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * @author Stuart Douglas
 */

@Mojo(name = "build", requiresDependencyResolution = ResolutionScope.RUNTIME)
@Execute(phase = LifecyclePhase.PACKAGE)
public class BuildMojo extends AbstractMojo {

    int folderCount = 0;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    /**
     * Commands to run before the deployment
     */
    @Parameter(alias = "config-file", required = true)
    private String configFile;

    @Parameter(defaultValue = "${basedir}", alias = "config-dir")
    private File configDir;

    @Parameter(defaultValue = "${project.build.finalName}", alias = "server-name")
    private String serverName;

    @Parameter(defaultValue = "${project.build.directory}")
    private String buildName;

    private final List<Runnable> cleanupTasks = new ArrayList<>();

    private final Map<String, Artifact> artifactMap = new HashMap<>();

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        buildArtifactMap();

        FileInputStream configStream = null;
        try {
            configStream = new FileInputStream(new File(configDir, configFile));
            final Build build = new BuildModelParser(project.getProperties()).parse(configStream);
            for (Server server : build.getServers()) {
                extractServer(server);
            }
            copyServers(build);
            copyModules(build);
            copyArtifacts(build);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            safeClose(configStream);
            for (Runnable task : cleanupTasks) {
                try {
                    task.run();
                } catch (Exception e) {
                    getLog().error("Failed to cleanup", e);
                }
            }
        }
    }

    private void copyModules(Build build) throws IOException {
        final List<Map<ModuleIdentifier, ModuleParseResult>> allModules = new ArrayList<>();
        for (Server server : build.getServers()) {
            allModules.add(ModuleUtils.enumerateModuleDirectory(getLog(), Paths.get(server.getPath())));
        }

        Deque<ModuleParseResult> transitiveIncludes = new ArrayDeque<>();
        final Map<ModuleIdentifier, ModuleParseResult> finalModuleSet = new HashMap<>();

        //go through and get all directly included modules
        for (int i = 0; i < allModules.size(); ++i) {
            Server server = build.getServers().get(i);
            Map<ModuleIdentifier, ModuleParseResult> modules = allModules.get(i);
            for (Map.Entry<ModuleIdentifier, ModuleParseResult> entry : modules.entrySet()) {
                Server.ModuleIncludeType includeType = server.includeModule(entry.getKey().toString());
                if (includeType == Server.ModuleIncludeType.MODULE_ONLY || includeType == Server.ModuleIncludeType.TRANSITIVE) {
                    if (finalModuleSet.containsKey(entry.getKey())) {
                        throw new RuntimeException("Same module is present in two servers, one of them must be excluded " + finalModuleSet.get(entry.getKey()).moduleXmlFile + " and " + entry.getValue().moduleXmlFile);
                    }
                    finalModuleSet.put(entry.getKey(), entry.getValue());
                    if (includeType == Server.ModuleIncludeType.TRANSITIVE) {
                        transitiveIncludes.add(entry.getValue());
                    }
                }
            }
        }
        final Set<String> notFound = new HashSet<>();
        //now resolve all transitive dependencies
        while (!transitiveIncludes.isEmpty()) {
            ModuleParseResult transitive = transitiveIncludes.pop();
            for (ModuleParseResult.ModuleDependency dep : transitive.getDependencies()) {
                if (!finalModuleSet.containsKey(dep.getModuleId())) {
                    ModuleParseResult found = null;
                    for (Map<ModuleIdentifier, ModuleParseResult> moduleMap : allModules) {
                        if (moduleMap.containsKey(dep.getModuleId())) {
                            if (found == null) {
                                found = moduleMap.get(dep.getModuleId());
                            } else {
                                throw new RuntimeException("Same module is present in two servers, one of them must be excluded " + moduleMap.get(dep.getModuleId()) + " and " + found.moduleXmlFile);
                            }
                        }
                    }
                    if (found == null && !dep.isOptional()) {
                        notFound.add("Could not find module " + dep.getModuleId() + " referenced from module " + transitive.getIdentifier() + " at " + transitive.getModuleXmlFile());
                    } else if (found != null) {
                        finalModuleSet.put(found.getIdentifier(), found);
                        transitiveIncludes.add(found);
                    } else {
                        getLog().warn("Could not find optional dependency " + dep.getModuleId());
                    }
                }
            }
        }
        if(!notFound.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for(String problem : notFound) {
                sb.append(problem);
                sb.append('\n');
            }
            throw new RuntimeException(sb.toString());
        }

        Properties artifactPropertyMap = new Properties();
        for(Map.Entry<String, Artifact> entry : artifactMap.entrySet()) {
            StringBuilder sb = new StringBuilder();
            sb.append(entry.getValue().getGroupId());
            sb.append(":");
            sb.append(entry.getValue().getArtifactId());
            sb.append(":");
            sb.append(entry.getValue().getVersion());
            if(entry.getValue().getClassifier() != null) {
                sb.append(':');
                sb.append(entry.getValue().getClassifier());
            }
            artifactPropertyMap.put(entry.getKey(), sb.toString());
        }

        final BuildPropertyReplacer moduleReplacer = new BuildPropertyReplacer(artifactPropertyMap);
        File baseDir = new File(buildName, serverName);
        Path modulesDir = Paths.get(baseDir.getAbsolutePath()).resolve("modules");
        //ok, now we have a resolved module set
        //now lets copy it to where it needs to go
        for (Map.Entry<ModuleIdentifier, ModuleParseResult> entry : finalModuleSet.entrySet()) {
            Path moduleRoot = entry.getValue().getModuleRoot();
            Path module = entry.getValue().getModuleXmlFile();
            final Path moduleParent = module.getParent();
            final Path relativeParent = moduleRoot.relativize(moduleParent);
            final Path targetDir = modulesDir.resolve(relativeParent);
            if (!Files.isDirectory(targetDir)) {
                targetDir.toFile().mkdirs();
            }

            Files.walkFileTree(moduleParent, new FileVisitor<Path>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    String relative = moduleParent.relativize(dir).toString();
                    Path rel = targetDir.resolve(relative);
                    if (!Files.isDirectory(rel)) {
                        if (!rel.toFile().mkdirs()) {
                            throw new IOException("Could not create directory " + rel.toString());
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String relative = moduleParent.relativize(file).toString();
                    Path targetFile = moduleParent.resolve(relative);
                    if (relative.equals("module.xml")) {
                        //we do property replacement on module.xml
                        //TODO: this is a bit yuck atm
                        String data = readFile(file.toFile());
                        data = moduleReplacer.replaceProperties(data);
                        copyFile(new ByteArrayInputStream(data.getBytes("UTF-8")), targetFile.toFile());
                    } else {
                        copyFile(file.toFile(), targetFile.toFile());
                        Files.setPosixFilePermissions(targetFile, Files.getPosixFilePermissions(file));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    return FileVisitResult.TERMINATE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    return FileVisitResult.CONTINUE;
                }
            });

        }


    }

    private void buildArtifactMap() {
        for (Artifact artifact : project.getArtifacts()) {
            StringBuilder sb = new StringBuilder();
            sb.append(artifact.getGroupId());
            sb.append(':');
            sb.append(artifact.getArtifactId());
            if (artifact.getClassifier() != null && !artifact.getClassifier().isEmpty()) {
                sb.append(":");
                sb.append(artifact.getClassifier());
            }
            artifactMap.put(sb.toString(), artifact);
            sb.append(":");
            artifactMap.put(sb.toString(), artifact);
        }

    }

    private void extractServer(Server server) {
        if (server.getPath() != null) {
            return;
        }
        String tempDir = System.getProperty("java.io.tmpdir");
        String name = "wf-server-build" + (folderCount++);
        final File destDir = new File(tempDir, name);
        deleteRecursive(destDir);
        cleanupTasks.add(new Runnable() {
            @Override
            public void run() {
                deleteRecursive(destDir);
            }
        });
        destDir.mkdirs();
        server.setPath(destDir.getAbsolutePath());
        Artifact artifact = artifactMap.get(server.getArtifact());
        if (artifact == null) {
            throw new RuntimeException("Could not find server artifact " + server.getArtifact() + " make sure it is present as a dependency of the project");
        }

        artifact.getFile();
        JarFile jar = null;
        try {
            jar = new JarFile(artifact.getFile());
            Enumeration<JarEntry> entries = jar.entries();
            byte[] data = new byte[1024];
            while (entries.hasMoreElements()) {
                JarEntry jarFile = entries.nextElement();
                java.io.File f = new java.io.File(destDir + java.io.File.separator + jarFile.getName());
                if (destDir.isDirectory()) { // if its a directory, create it
                    f.mkdir();
                    continue;
                }
                InputStream is = jar.getInputStream(jarFile); // get the input stream
                FileOutputStream fos = new java.io.FileOutputStream(f);
                try {
                    int read;
                    while ((read = is.read(data)) > 0) {  // write contents of 'is' to 'fos'
                        fos.write(data, 0, read);
                    }
                } finally {
                    safeClose(is, fos);
                }
                Path p = Paths.get(f.getAbsolutePath());
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            safeClose(jar);
        }
    }

    private void copyArtifacts(Build build) throws IOException {
        File baseDir = new File(buildName, serverName);
        for (CopyArtifact copy : build.getCopyArtifacts()) {
            File target = new File(baseDir, copy.getToLocation());
            if (!target.getParentFile().isDirectory()) {
                if (!target.getParentFile().mkdirs()) {
                    throw new IOException("Could not create directory " + target.getParentFile());
                }
            }
            Artifact artifact = artifactMap.get(copy.getArtifact());
            if (artifact == null) {
                throw new RuntimeException("Could not find artifact " + copy.getArtifact() + " make sure it is a dependency of the project");
            }
            copyFile(artifact.getFile(), target);
        }
    }

    public void copyServers(Build build) throws IOException {
        File baseDir = new File(buildName, serverName);
        deleteRecursive(baseDir);

        final Path path = Paths.get(baseDir.getAbsolutePath());
        for (final Server server : build.getServers()) {
            final Path base = Paths.get(server.getPath());
            Files.walkFileTree(base, new FileVisitor<Path>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    String relative = base.relativize(dir).toString();
                    boolean include = server.includeFile(relative);
                    if (include) {
                        Path rel = path.resolve(relative);
                        if (!Files.isDirectory(rel)) {
                            if (!rel.toFile().mkdirs()) {
                                throw new IOException("Could not create directory " + rel.toString());
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                    return FileVisitResult.SKIP_SUBTREE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String relative = base.relativize(file).toString();
                    if (!server.includeFile(relative)) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path targetFile = path.resolve(relative);
                    copyFile(file.toFile(), targetFile.toFile());
                    Files.setPosixFilePermissions(targetFile, Files.getPosixFilePermissions(file));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    return FileVisitResult.TERMINATE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    return FileVisitResult.CONTINUE;
                }
            });
        }


    }

    private void safeClose(final Closeable... closeable) {
        for (Closeable c : closeable) {
            if (c != null) {
                try {
                    c.close();
                } catch (IOException e) {
                    getLog().error("Failed to close resource", e);
                }
            }
        }
    }


    public void deleteRecursive(final File file) {
        File[] files = file.listFiles();
        if (files != null) {
            for (File f : files) {
                deleteRecursive(f);
            }
        }
        file.delete();
    }

    public void copyFile(final File src, final File dest) throws IOException {
        final InputStream in = new BufferedInputStream(new FileInputStream(src));
        try {
            copyFile(in, dest);
        } finally {
            safeClose(in);
        }
    }

    public void copyFile(final InputStream in, final File dest) throws IOException {
        dest.getParentFile().mkdirs();
        byte[] data = new byte[10000];
        final OutputStream out = new BufferedOutputStream(new FileOutputStream(dest));
        try {
            int read;
            while ((read = in.read(data)) > 0) {
                out.write(data, 0, read);
            }
        } finally {
            safeClose(out);
        }
    }


    public static String readFile(final File file) {
        try {
            return readFile(new FileInputStream(file));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String readFile(InputStream file) {
        BufferedInputStream stream = null;
        try {
            stream = new BufferedInputStream(file);
            byte[] buff = new byte[1024];
            StringBuilder builder = new StringBuilder();
            int read = -1;
            while ((read = stream.read(buff)) != -1) {
                builder.append(new String(buff, 0, read));
            }
            return builder.toString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                    //ignore
                }
            }
        }
    }
}