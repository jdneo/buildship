/*******************************************************************************
 * Copyright (c) 2019 Gradle Inc.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 ******************************************************************************/
package org.eclipse.buildship.core.internal.workspace;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import org.gradle.tooling.model.eclipse.ClasspathAttribute;
import org.gradle.tooling.model.eclipse.EclipseSourceDirectory;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;

import org.eclipse.buildship.core.internal.util.gradle.CompatEclipseClasspathEntry;
import org.eclipse.buildship.core.internal.util.gradle.CompatEclipseSourceDirectory;

/**
 * Updates the source folders of the target project.
 * <p/>
 * The update is triggered via {@link #update(IJavaProject, List, IProgressMonitor)}. The method
 * executes synchronously and unprotected, without thread synchronization or job scheduling.
 * <p/>
 * The update logic applies the following rules on all source folders:
 * <ul>
 * <li>If it is defined in the Gradle model and it doesn't exist in the project, then it will be
 * created. Note that source folders are only created if they physically exist on disk.</li>
 * <li>If it is no longer part of the Gradle model, then it will be deleted.</li>
 * <li>The attributes, output directory and includes/excludes are only modified if present in the
 * Gradle model.</li>
 * </ul>
 */
final class SourceFolderUpdater {

    private final IJavaProject project;
    private final Map<IPath, File> sourceFoldersByPath;
    private final File mainJavaOutputDirectory;
    private final File mainResourceOutputDirectory;
    private final File testJavaOutputDirectory;
    private final File testResourceOutputDirectory;

    private SourceFolderUpdater(IJavaProject project, List<EclipseSourceDirectory> sourceDirectories,
    		 Map<String, Object> classpathInfo) {
        this.project = Preconditions.checkNotNull(project);
        this.sourceFoldersByPath = Maps.newLinkedHashMap();
        IPath projectLocation = project.getProject().getRawLocation();
        
        mainJavaOutputDirectory = (File) classpathInfo.get("mainJavaOutput");
        if (mainJavaOutputDirectory != null) {
        	Set<File> mainJava = (Set<File>) classpathInfo.getOrDefault("mainJava", Collections.emptySet());
            for (File mainJavaDirectory : mainJava) {
            	IPath fullPath = project.getProject().getFullPath().append(new Path(mainJavaDirectory.toString()).makeRelativeTo(projectLocation));
            	this.sourceFoldersByPath.put(fullPath, mainJavaDirectory);
            }
            
            File mainGeneratedDirectory = (File) classpathInfo.get("mainGeneratedDirectory");
            if (mainGeneratedDirectory != null) {
            	IPath fullPath = project.getProject().getFullPath().append(new Path(mainGeneratedDirectory.toString()).makeRelativeTo(projectLocation));
            	this.sourceFoldersByPath.put(fullPath, mainGeneratedDirectory);
            }
        }
        
        mainResourceOutputDirectory = (File) classpathInfo.get("mainResourcesOutput");
        if (mainResourceOutputDirectory != null) {
        	Set<File> mainResources = (Set<File>) classpathInfo.getOrDefault("mainResources", Collections.emptySet());
            for (File mainResourceDirectory : mainResources) {
            	IPath fullPath = project.getProject().getFullPath().append(new Path(mainResourceDirectory.toString()).makeRelativeTo(projectLocation));
            	this.sourceFoldersByPath.put(fullPath, mainResourceDirectory);
            }
        }
        
        testJavaOutputDirectory = (File) classpathInfo.get("testJavaOutput");
        if (testJavaOutputDirectory != null) {
        	Set<File> testJava = (Set<File>) classpathInfo.getOrDefault("testJava", Collections.emptySet());
            for (File testJavaDirectory : testJava) {
            	IPath fullPath = project.getProject().getFullPath().append(new Path(testJavaDirectory.toString()).makeRelativeTo(projectLocation));
            	this.sourceFoldersByPath.put(fullPath, testJavaDirectory);
            }
            
            File testGeneratedDirectory = (File) classpathInfo.get("testGeneratedDirectory");
            if (testGeneratedDirectory != null) {
            	IPath fullPath = project.getProject().getFullPath().append(new Path(testGeneratedDirectory.toString()).makeRelativeTo(projectLocation));
            	this.sourceFoldersByPath.put(fullPath, testGeneratedDirectory);
            }
        }
        
        testResourceOutputDirectory = (File) classpathInfo.get("testResourcesOutput");
        if (testResourceOutputDirectory != null) {
        	Set<File> testResources = (Set<File>) classpathInfo.getOrDefault("testResources", Collections.emptySet());
            for (File testResourceDirectory : testResources) {
            	IPath fullPath = project.getProject().getFullPath().append(new Path(testResourceDirectory.toString()).makeRelativeTo(projectLocation));
            	this.sourceFoldersByPath.put(fullPath, testResourceDirectory);
            }
        }
    }

    private void updateSourceFolders(IProgressMonitor monitor) throws JavaModelException {
        List<IClasspathEntry> classpath = Lists.newArrayList(this.project.getRawClasspath());
        updateExistingSourceFolders(classpath);
        addNewSourceFolders(classpath);
        this.project.setRawClasspath(classpath.toArray(new IClasspathEntry[0]), monitor);
    }

    private void updateExistingSourceFolders(List<IClasspathEntry> classpath) {
        ListIterator<IClasspathEntry> iterator = classpath.listIterator();
        while (iterator.hasNext()) {
            IClasspathEntry classpathEntry = iterator.next();
            if (classpathEntry.getEntryKind() == IClasspathEntry.CPE_SOURCE) {
                IPath path = classpathEntry.getPath();
                File sourceFolder = this.sourceFoldersByPath.get(path);
                if (sourceFolder == null) {
                    iterator.remove();
            	} else {
                	iterator.set(toClasspathEntry(sourceFolder, classpathEntry));
                    this.sourceFoldersByPath.remove(path);
                }
            }
        }
    }

//    private IClasspathEntry toClasspathEntry(EclipseSourceDirectory sourceFolder, IClasspathEntry existingEntry) {
//        SourceFolderEntryBuilder builder = new SourceFolderEntryBuilder(this.project, existingEntry.getPath());
//        builder.setOutput(existingEntry.getOutputLocation());
//        builder.setAttributes(existingEntry.getExtraAttributes());
//        builder.setIncludes(existingEntry.getInclusionPatterns());
//        builder.setExcludes(existingEntry.getExclusionPatterns());
//        synchronizeAttributesFromModel(builder, sourceFolder);
//        return builder.build();
//    }
    
    private IClasspathEntry toClasspathEntry(File generatedDirectory, IClasspathEntry existingEntry) {
        SourceFolderEntryBuilder builder = new SourceFolderEntryBuilder(this.project, existingEntry.getPath());
        builder.setOutput(existingEntry.getOutputLocation());
        List<IClasspathAttribute> attributes = new ArrayList<>(Arrays.asList(existingEntry.getExtraAttributes()));
        attributes.add(JavaCore.newClasspathAttribute(IClasspathAttribute.OPTIONAL, "true"));
        builder.setAttributes(attributes.toArray(IClasspathAttribute[]::new));
        builder.setIncludes(existingEntry.getInclusionPatterns());
        builder.setExcludes(existingEntry.getExclusionPatterns());
        return builder.build();
    }

    private void synchronizeAttributesFromModel(SourceFolderEntryBuilder builder, EclipseSourceDirectory sourceFolder) {
        if (CompatEclipseSourceDirectory.supportsOutput(sourceFolder)) {
            builder.setOutput(sourceFolder.getOutput());
        }

        if (CompatEclipseClasspathEntry.supportsAttributes(sourceFolder)) {
            builder.setAttributes(sourceFolder.getClasspathAttributes());
        }

        if (CompatEclipseSourceDirectory.supportsExcludes(sourceFolder)) {
            builder.setExcludes(sourceFolder.getExcludes());
        }

        if (CompatEclipseSourceDirectory.supportsIncludes(sourceFolder)) {
            builder.setIncludes(sourceFolder.getIncludes());
        }
    }

    private void addNewSourceFolders(List<IClasspathEntry> classpath) {
        for (File sourceFolder : this.sourceFoldersByPath.values()) {
            IResource physicalLocation = getUnderlyingDirectory(sourceFolder);
//            if (existsInSameLocation(physicalLocation, sourceFolder)) {
                classpath.add(toClasspathEntry(sourceFolder, physicalLocation, sourceFolder.toString().contains("test")));
//            }
        }
    }

    private IResource getUnderlyingDirectory(EclipseSourceDirectory directory) {
        IProject project = this.project.getProject();
        IPath path = project.getFullPath().append(directory.getPath());
        if (path.segmentCount() == 1) {
            return project;
        }
        return project.getFolder(path.removeFirstSegments(1));
    }
    
    private IResource getUnderlyingDirectory(File directory) {
        IProject project = this.project.getProject();
        IPath path = project.getFullPath().append(new Path(directory.toString()).makeRelativeTo(project.getLocation()));
        if (path.segmentCount() == 1) {
            return project;
        }
        return project.getFolder(path.removeFirstSegments(1));
    }

    private boolean existsInSameLocation(IResource directory, EclipseSourceDirectory sourceFolder) {
        if (!directory.exists() && !isOptional(sourceFolder)) {
            return false;
        }
        if (directory.isLinked()) {
            return hasSameLocationAs(directory, sourceFolder);
        }
        return true;
    }
    
    private boolean isOptional(EclipseSourceDirectory sourceFolder) {
        for (ClasspathAttribute attribute : sourceFolder.getClasspathAttributes()) {
            if (IClasspathAttribute.OPTIONAL.equals(attribute.getName()) && "true".equals(attribute.getValue())) { //$NON-NLS-1$
                return true;
            }
        }
        return false;
    }

    private boolean hasSameLocationAs(IResource directory, EclipseSourceDirectory sourceFolder) {
        return directory.getLocation() != null && directory.getLocation().toFile().equals(sourceFolder.getDirectory());
    }

//    private IClasspathEntry toClasspathEntry(EclipseSourceDirectory sourceFolder, IResource physicalLocation) {
//        IPackageFragmentRoot fragmentRoot = this.project.getPackageFragmentRoot(physicalLocation);
//        SourceFolderEntryBuilder builder = new SourceFolderEntryBuilder(this.project, fragmentRoot.getPath());
//        synchronizeAttributesFromModel(builder, sourceFolder);
//        return builder.build();
//    }
    
    private IClasspathEntry toClasspathEntry(File folder, IResource physicalLocation, boolean isTest) {
        IPackageFragmentRoot fragmentRoot = this.project.getPackageFragmentRoot(physicalLocation);
        SourceFolderEntryBuilder builder = new SourceFolderEntryBuilder(this.project, fragmentRoot.getPath());
        List<IClasspathAttribute> attributes = new LinkedList<>();
        boolean isOptional = folder.toString().contains("generated");
        if (isOptional) {
            attributes.add(JavaCore.newClasspathAttribute(IClasspathAttribute.OPTIONAL, "true"));
        }
        if (isTest) {
        	attributes.add(JavaCore.newClasspathAttribute(IClasspathAttribute.TEST, "true"));
        }
        builder.setAttributes(attributes.toArray(IClasspathAttribute[]::new));

        IPath projectLocation = project.getProject().getRawLocation();
        IPath outputPath;
        // TODO: separate java and resources
        if (isTest) {
        	outputPath = project.getProject().getFullPath().append(new Path(testJavaOutputDirectory.toString()).makeRelativeTo(projectLocation));
        } else {
        	outputPath = project.getProject().getFullPath().append(new Path(mainJavaOutputDirectory.toString()).makeRelativeTo(projectLocation));
        }
        builder.setOutput(outputPath);
        return builder.build();
    }

    /**
     * Updates the source folders on the target project.
     *
     * @param project the target project to update the source folders on
     * @param sourceDirectories the list of source folders from the Gradle model to assign to the
     *            project
     * @param monitor the monitor to report progress on
     * @throws JavaModelException if the classpath modification fails
     */
    public static void update(IJavaProject project, List<EclipseSourceDirectory> sourceDirectories,  Map<String, Object> classpathInfo, IProgressMonitor monitor) throws JavaModelException {
        SourceFolderUpdater updater = new SourceFolderUpdater(project, sourceDirectories, classpathInfo);
        updater.updateSourceFolders(monitor);
    }

    /**
     * Helper class to create an {@link IClasspathEntry} instance representing a source folder.
     */
    private static class SourceFolderEntryBuilder {

        private final IPath path;
        private IPath output = null;
        private IPath[] includes = new IPath[0];
        private IPath[] excludes = new IPath[0];
        private IClasspathAttribute[] attributes = new IClasspathAttribute[0];
        private IJavaProject project;

        public SourceFolderEntryBuilder(IJavaProject project, IPath path) {
            this.project = project;
            this.path = path;
        }

        public void setOutput(IPath output) {
            this.output = output;
        }

        public void setOutput(String output) {
            this.output = output != null ? this.project.getPath().append(output) : null;
        }

        public void setIncludes(IPath[] includes) {
            this.includes = includes;
        }

        public void setIncludes(List<String> includes) {
            this.includes = stringListToPaths(includes);
        }

        public void setExcludes(IPath[] excludes) {
            this.excludes = excludes;
        }

        public void setExcludes(List<String> excludes) {
            this.excludes = stringListToPaths(excludes);
        }

        public void setAttributes(IClasspathAttribute[] attributes) {
            this.attributes = attributes;
        }

        public void setAttributes(Iterable<? extends ClasspathAttribute> attributes) {
            List<ClasspathAttribute> attributeList = Lists.newArrayList(attributes);
            this.attributes = new IClasspathAttribute[attributeList.size()];
            for (int i = 0; i < attributeList.size(); i++) {
                ClasspathAttribute attribute = attributeList.get(i);
                this.attributes[i] = JavaCore.newClasspathAttribute(attribute.getName(), attribute.getValue());
            }
        }

        public IClasspathEntry build() {
            return JavaCore.newSourceEntry(this.path, this.includes, this.excludes, this.output, this.attributes);
        }

        private static IPath[] stringListToPaths(List<String> strings) {
            IPath[] result = new IPath[strings.size()];
            for (int i = 0; i < strings.size(); i++) {
                result[i] = new Path(strings.get(i));
            }
            return result;
        }
    }

}
