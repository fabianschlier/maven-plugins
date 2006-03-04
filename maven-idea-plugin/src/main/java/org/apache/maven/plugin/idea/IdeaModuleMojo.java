package org.apache.maven.plugin.idea;

/*
 * Copyright 2005-2006 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.manager.WagonManager;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.apache.maven.wagon.ResourceDoesNotExistException;
import org.apache.maven.wagon.TransferFailedException;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.codehaus.plexus.util.xml.Xpp3DomWriter;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Edwin Punzalan
 * @goal module
 * @execute phase="generate-sources"
 * @todo use dom4j or something. Xpp3Dom can't cope properly with entities and so on
 */
public class IdeaModuleMojo
    extends AbstractIdeaMojo
{
    /**
     * The Maven Project.
     *
     * @parameter expression="${executedProject}"
     */
    private MavenProject executedProject;

    /**
     * The reactor projects in a multi-module build.
     *
     * @parameter expression="${reactorProjects}"
     * @required
     * @readonly
     */
    private List reactorProjects;

    /**
     * @parameter expression="${component.org.apache.maven.artifact.manager.WagonManager}"
     * @required
     * @readonly
     */
    private WagonManager wagonManager;

    /**
     * Whether to link the reactor projects as dependency modules or as libraries.
     *
     * @parameter expression="${linkModules}" default-value="true"
     */
    private boolean linkModules;

    /**
     * Whether to use full artifact names when referencing libraries.
     *
     * @parameter expression="${useFullNames}" default-value="false"
     */
    private boolean useFullNames;

    /**
     * Switch to enable or disable the inclusion of sources and javadoc references to the project's library
     *
     * @parameter expression="${useClassifiers}" default-value="false"
     */
    private boolean useClassifiers;

    /**
     * Sets the classifier string attached to an artifact source archive name
     *
     * @parameter expression="${sourceClassifier}" default-value="sources"
     */
    private String sourceClassifier;

    /**
     * Sets the classifier string attached to an artifact javadoc archive name
     *
     * @parameter expression="${javadocClassifier}" default-value="javadoc"
     */
    private String javadocClassifier;

    /**
     * An optional set of Library objects that allow you to specify a comma separated list of source dirs, class dirs,
     * or to indicate that the library should be excluded from the module. For example:
     * <p/>
     * <pre>
     * &lt;libraries&gt;
     *  &lt;library&gt;
     *      &lt;name&gt;webwork&lt;/name&gt;
     *      &lt;sources&gt;file://$webwork$/src/java&lt;/sources&gt;
     *      &lt;!--
     *      &lt;classes&gt;...&lt;/classes&gt;
     *      &lt;exclude&gt;true&lt;/exclude&gt;
     *      --&gt;
     *  &lt;/library&gt;
     * &lt;/libraries&gt;
     * </pre>
     *
     * @parameter
     */
    private Library[] libraries;

    /**
     * A comma-separated list of directories that should be excluded. These directories are in addition to those
     * already excluded, such as target.
     *
     * @parameter
     */
    private String exclude;

    /**
     * A temporary cache of artifacts that's already been downloaded or
     * attempted to be downloaded. This is to refrain from trying to download a
     * dependency that we have already tried to download.
     *
     * @todo this is nasty! the only reason this is static is to use the same cache between reactor calls
     */
    private static Map attemptedDownloads = new HashMap();

    private Set macros;

    public void initParam( MavenProject project, ArtifactFactory artifactFactory, ArtifactRepository localRepo,
                           ArtifactResolver artifactResolver, ArtifactMetadataSource artifactMetadataSource, Log log,
                           boolean overwrite, MavenProject executedProject, List reactorProjects,
                           WagonManager wagonManager, boolean linkModules, boolean useFullNames, boolean useClassifiers,
                           String sourceClassifier, String javadocClassifier, Library[] libraries, Set macros,
                           String exclude )
    {
        super.initParam( project, artifactFactory, localRepo, artifactResolver, artifactMetadataSource, log,
                         overwrite );

        this.executedProject = executedProject;

        this.reactorProjects = reactorProjects;

        this.wagonManager = wagonManager;

        this.linkModules = linkModules;

        this.useFullNames = useFullNames;

        this.useClassifiers = useClassifiers;

        this.sourceClassifier = sourceClassifier;

        this.javadocClassifier = javadocClassifier;

        this.libraries = libraries;

        this.macros = macros;

        this.exclude = exclude;
    }

    /**
     * Create IDEA (.iml) project files.
     *
     * @throws org.apache.maven.plugin.MojoExecutionException
     *
     */
    public void execute()
        throws MojoExecutionException
    {
        try
        {
            doDependencyResolution( project, artifactFactory, artifactResolver, localRepo, artifactMetadataSource );
        }
        catch ( Exception e )
        {
            throw new MojoExecutionException( "Unable to build project dependencies.", e );
        }

        File moduleFile = new File( project.getBasedir(), project.getArtifactId() + ".iml" );
        try
        {
            Reader reader;
            if ( moduleFile.exists() && !overwrite )
            {
                reader = new FileReader( moduleFile );
            }
            else
            {
                reader = getXmlReader( "module.xml" );
            }

            Xpp3Dom module;
            try
            {
                module = Xpp3DomBuilder.build( reader );
            }
            finally
            {
                IOUtil.close( reader );
            }

            // TODO: how can we let the WAR/EJBs plugin hook in and provide this?
            // TODO: merge in ejb-module, etc.
            if ( "war".equals( project.getPackaging() ) )
            {
                addWebModule( module );
            }
            else if ( "ejb".equals( project.getPackaging() ) )
            {
                module.setAttribute( "type", "J2EE_EJB_MODULE" );
            }

            Xpp3Dom component = findComponent( module, "NewModuleRootManager" );
            Xpp3Dom output = findElement( component, "output" );
            output.setAttribute( "url", getModuleFileUrl( project.getBuild().getOutputDirectory() ) );

            Xpp3Dom outputTest = findElement( component, "output-test" );
            outputTest.setAttribute( "url", getModuleFileUrl( project.getBuild().getTestOutputDirectory() ) );

            Xpp3Dom content = findElement( component, "content" );

            removeOldElements( content, "sourceFolder" );

            for ( Iterator i = executedProject.getCompileSourceRoots().iterator(); i.hasNext(); )
            {
                String directory = (String) i.next();
                addSourceFolder( content, directory, false );
            }
            for ( Iterator i = executedProject.getTestCompileSourceRoots().iterator(); i.hasNext(); )
            {
                String directory = (String) i.next();
                addSourceFolder( content, directory, true );
            }

            List resourceDirectory = new ArrayList();
            for ( Iterator i = project.getBuild().getResources().iterator(); i.hasNext(); )
            {
                Resource resource = (Resource) i.next();
                resourceDirectory.add( resource.getDirectory() );
            }

            for ( Iterator i = project.getBuild().getTestResources().iterator(); i.hasNext(); )
            {
                Resource resource = (Resource) i.next();
                String directory = resource.getDirectory();
                addSourceFolder( content, directory, true );
            }

            removeOldElements( content, "excludeFolder" );

            //For excludeFolder
            File target = new File( project.getBuild().getDirectory() );
            File classes = new File( project.getBuild().getOutputDirectory() );
            File testClasses = new File( project.getBuild().getTestOutputDirectory() );

            List sourceFolders = Arrays.asList( content.getChildren( "sourceFolder" ) );

            List filteredExcludes = new ArrayList();
            filteredExcludes.addAll( getExcludedDirectories( target, filteredExcludes, sourceFolders ) );
            filteredExcludes.addAll( getExcludedDirectories( classes, filteredExcludes, sourceFolders ) );
            filteredExcludes.addAll( getExcludedDirectories( testClasses, filteredExcludes, sourceFolders ) );

            if ( exclude != null )
            {
                String[] dirs = exclude.split( "[,\\s]+" );
                for ( int i = 0; i < dirs.length; i++ )
                {
                    File excludedDir = new File( dirs[ i ] );
                    filteredExcludes.add( getExcludedDirectories( excludedDir, filteredExcludes, sourceFolders ) );
                }
            }

            for ( Iterator i = filteredExcludes.iterator(); i.hasNext(); )
            {
                addExcludeFolder( content, i.next().toString() );
            }

            removeOldDependencies( component );

            List testClasspathElements = project.getTestArtifacts();
            for ( Iterator i = testClasspathElements.iterator(); i.hasNext(); )
            {
                Artifact a = (Artifact) i.next();

                Library library = findLibrary( a );
                if ( library != null && library.isExclude() )
                {
                    continue;
                }

                String moduleName;
                if ( useFullNames )
                {
                    moduleName = a.getGroupId() + ':' + a.getArtifactId() + ':' + a.getType() + ':' + a.getVersion();
                }
                else
                {
                    moduleName = a.getArtifactId();
                }

                Xpp3Dom dep = null;

                Xpp3Dom[] orderEntries = component.getChildren( "orderEntry" );
                for ( int idx = 0; idx < orderEntries.length; idx++ )
                {
                    Xpp3Dom orderEntry = orderEntries[idx];

                    if ( orderEntry.getAttribute( "type" ).equals( "module" ) )
                    {
                        if ( orderEntry.getAttribute( "module-name" ).equals( moduleName ) )
                        {
                            dep = orderEntry;
                            break;
                        }
                    }
                    else if ( orderEntry.getAttribute( "type" ).equals( "module-library" ) )
                    {
                        Xpp3Dom lib = orderEntry.getChild( "library" );
                        if ( lib.getAttribute( "name" ).equals( moduleName ) )
                        {
                            dep = orderEntry;
                            break;
                        }
                    }
                }

                if ( dep == null )
                {
                    dep = createElement( component, "orderEntry" );
                }

                boolean isIdeaModule = false;
                if ( linkModules )
                {
                    isIdeaModule = isReactorProject( a.getGroupId(), a.getArtifactId() );

                    if ( isIdeaModule )
                    {
                        dep.setAttribute( "type", "module" );
                        dep.setAttribute( "module-name", moduleName );
                    }
                }

                if ( a.getFile() != null && !isIdeaModule )
                {
                    dep.setAttribute( "type", "module-library" );
                    removeOldElements( dep, "library" );
                    dep = createElement( dep, "library" );
                    dep.setAttribute( "name", moduleName );

                    Xpp3Dom el = createElement( dep, "CLASSES" );
                    if ( library != null && library.getSplitClasses().length > 0 )
                    {
                        String[] libraryClasses = library.getSplitClasses();
                        for ( int k = 0; k < libraryClasses.length; k++ )
                        {
                            String classpath = libraryClasses[k];
                            extractMacro( classpath );
                            Xpp3Dom classEl = createElement( el, "root" );
                            classEl.setAttribute( "url", classpath );
                        }
                    }
                    else
                    {
                        el = createElement( el, "root" );
                        File file = a.getFile();
                        el.setAttribute( "url", "jar://" + file.getAbsolutePath().replace( '\\', '/' ) + "!/" );
                    }

                    boolean usedSources = false;
                    if ( library != null && library.getSplitSources().length > 0 )
                    {
                        Xpp3Dom sourcesElement = createElement( dep, "SOURCES" );
                        usedSources = true;
                        String[] sources = library.getSplitSources();
                        for ( int k = 0; k < sources.length; k++ )
                        {
                            String source = sources[k];
                            extractMacro( source );
                            Xpp3Dom sourceEl = createElement( sourcesElement, "root" );
                            sourceEl.setAttribute( "url", source );
                        }
                    }

                    if ( useClassifiers )
                    {
                        resolveClassifier( createElement( dep, "JAVADOC" ), a, javadocClassifier );
                        if ( !usedSources )
                        {
                            resolveClassifier( createElement( dep, "SOURCES" ), a, sourceClassifier );
                        }
                    }
                }
            }

            for ( Iterator resourceDirs = resourceDirectory.iterator(); resourceDirs.hasNext(); )
            {
                String resourceDir = (String) resourceDirs.next();

                getLog().info( "Adding resource directory: " + resourceDir );

                addResources( component, resourceDir );
            }

            FileWriter writer = new FileWriter( moduleFile );
            try
            {
                Xpp3DomWriter.write( writer, module );
            }
            finally
            {
                IOUtil.close( writer );
            }
        }
        catch ( XmlPullParserException e )
        {
            throw new MojoExecutionException( "Error parsing existing IML file " + moduleFile.getAbsolutePath(), e );
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Error parsing existing IML file " + moduleFile.getAbsolutePath(), e );
        }
    }

    private void extractMacro( String path )
    {
        if ( macros != null )
        {
            Pattern p = Pattern.compile( ".*\\$([^\\$]+)\\$.*" );
            Matcher matcher = p.matcher( path );
            while ( matcher.find() )
            {
                String macro = matcher.group( 1 );
                macros.add( macro );
            }
        }
    }

    private Library findLibrary( Artifact a )
    {
        if ( libraries != null )
        {
            for ( int j = 0; j < libraries.length; j++ )
            {
                Library library = libraries[j];
                if ( a.getArtifactId().equals( library.getName() ) )
                {
                    return library;
                }
            }
        }

        return null;
    }

    private List getExcludedDirectories( File target, List excludeList, List sourceFolders )
    {
        List foundFolders = new ArrayList();
        int dirs = 0;

        if ( target.exists() && !excludeList.contains( target.getAbsolutePath() ) )
        {
            File[] files = target.listFiles();

            for ( int i = 0; i < files.length; i++ )
            {
                File file = files[i];
                if ( file.isDirectory() && !excludeList.contains( file.getAbsolutePath() ) )
                {
                    String absolutePath = file.getAbsolutePath();
                    String url = getModuleFileUrl( absolutePath );

                    for ( Iterator sources = sourceFolders.iterator(); sources.hasNext(); )
                    {
                        String source = ( (Xpp3Dom) sources.next() ).getAttribute( "url" );
                        if ( source.equals( url ) )
                        {
                            dirs++;
                            break;
                        }
                        else if ( source.indexOf( url ) == 0 )
                        {
                            dirs++;
                            foundFolders.addAll(
                                getExcludedDirectories( new File( absolutePath ), excludeList, sourceFolders ) );
                            break;
                        }
                        else
                        {
                            foundFolders.add( absolutePath );
                        }
                    }
                }
            }

            //if all directories are excluded, then just exclude the parent directory
            if ( dirs == 0 )
            {
                foundFolders.clear();

                foundFolders.add( target.getAbsolutePath() );
            }
        }

        return foundFolders;
    }

    /**
     * Adds the Web module to the (.iml) project file.
     *
     * @param module Xpp3Dom element
     */
    private void addWebModule( Xpp3Dom module )
    {
        // TODO: this is bad - reproducing war plugin defaults, etc!
        //   --> this is where the OGNL out of a plugin would be helpful as we could run package first and
        //       grab stuff from the mojo

/*
Can't run this anyway as Xpp3Dom is in both classloaders...
                Xpp3Dom configuration = project.getGoalConfiguration( "maven-war-plugin", "war" );
                String warWebapp = configuration.getChild( "webappDirectory" ).getValue();
                if ( warWebapp == null )
                {
                    warWebapp = project.getBuild().getDirectory() + "/" + project.getArtifactId();
                }
                String warSrc = configuration.getChild( "warSrc" ).getValue();
                if ( warSrc == null )
                {
                    warSrc = "src/main/webapp";
                }
                String webXml = configuration.getChild( "webXml" ).getValue();
                if ( webXml == null )
                {
                    webXml = warSrc + "/WEB-INF/web.xml";
                }
*/
        String warWebapp = project.getBuild().getDirectory() + "/" + project.getArtifactId();
        String warSrc = "src/main/webapp";
        String webXml = warSrc + "/WEB-INF/web.xml";

        module.setAttribute( "type", "J2EE_WEB_MODULE" );

        Xpp3Dom component = findComponent( module, "WebModuleBuildComponent" );
        Xpp3Dom setting = findSetting( component, "EXPLODED_URL" );
        setting.setAttribute( "value", getModuleFileUrl( warWebapp ) );

        component = findComponent( module, "WebModuleProperties" );

        removeOldElements( component, "containerElement" );
        List artifacts = project.getTestArtifacts();
        for ( Iterator i = artifacts.iterator(); i.hasNext(); )
        {
            Artifact artifact = (Artifact) i.next();

            Xpp3Dom containerElement = createElement( component, "containerElement" );

            boolean linkAsModule = false;
            if ( linkModules )
            {
                linkAsModule = isReactorProject( artifact.getGroupId(), artifact.getArtifactId() );
            }

            if ( linkAsModule )
            {
                containerElement.setAttribute( "type", "module" );
                containerElement.setAttribute( "name", artifact.getArtifactId() );
                Xpp3Dom methodAttribute = createElement( containerElement, "attribute" );
                methodAttribute.setAttribute( "name", "method" );
                methodAttribute.setAttribute( "value", "5" );
                Xpp3Dom uriAttribute = createElement( containerElement, "attribute" );
                uriAttribute.setAttribute( "name", "URI" );
                uriAttribute.setAttribute( "value", "/WEB-INF/classes" );
            }
            else if ( artifact.getFile() != null )
            {
                containerElement.setAttribute( "type", "library" );
                containerElement.setAttribute( "level", "module" );
                containerElement.setAttribute( "name", artifact.getArtifactId() );
                Xpp3Dom methodAttribute = createElement( containerElement, "attribute" );
                methodAttribute.setAttribute( "name", "method" );
                methodAttribute.setAttribute( "value", "1" ); // IntelliJ 5.0.2 is bugged and doesn't read it
                Xpp3Dom uriAttribute = createElement( containerElement, "attribute" );
                uriAttribute.setAttribute( "name", "URI" );
                uriAttribute.setAttribute( "value", "/WEB-INF/lib/" + artifact.getFile().getName() );
            }
        }

        Xpp3Dom element = findElement( component, "deploymentDescriptor" );
        if ( element.getAttribute( "version" ) == null )
        {
            // TODO: should derive from web.xml - does IDEA do this if omitted?
//                    element.setAttribute( "version", "2.3" );
        }
        if ( element.getAttribute( "name" ) == null )
        {
            element.setAttribute( "name", "web.xml" );
        }

        element.setAttribute( "url", getModuleFileUrl( webXml ) );

        element = findElement( component, "webroots" );
        removeOldElements( element, "root" );

        element = createElement( element, "root" );
        element.setAttribute( "relative", "/" );
        element.setAttribute( "url", getModuleFileUrl( warSrc ) );
    }

    /**
     * Translate the relative path of the file into module path
     *
     * @param basedir File to use as basedir
     * @param path    Absolute path string to translate to ModuleFileUrl
     * @return moduleFileUrl Translated Module File URL
     */
    private String getModuleFileUrl( File basedir, String path )
    {
        return "file://$MODULE_DIR$/" + toRelative( basedir, path );
    }

    private String getModuleFileUrl( String file )
    {
        return getModuleFileUrl( project.getBasedir(), file );
    }

    /**
     * Adds a sourceFolder element to IDEA (.iml) project file
     *
     * @param content   Xpp3Dom element
     * @param directory Directory to set as url.
     * @param isTest    True if directory isTestSource.
     */
    private void addSourceFolder( Xpp3Dom content, String directory, boolean isTest )
    {
        if ( !StringUtils.isEmpty( directory ) && new File( directory ).isDirectory() )
        {
            Xpp3Dom sourceFolder = createElement( content, "sourceFolder" );
            sourceFolder.setAttribute( "url", getModuleFileUrl( directory ) );
            sourceFolder.setAttribute( "isTestSource", Boolean.toString( isTest ) );
        }
    }

    private void addExcludeFolder( Xpp3Dom content, String directory )
    {
        if ( !StringUtils.isEmpty( directory ) && new File( directory ).isDirectory() )
        {
            Xpp3Dom excludeFolder = createElement( content, "excludeFolder" );
            excludeFolder.setAttribute( "url", getModuleFileUrl( directory ) );
        }
    }

    /**
     * Removes dependencies from Xpp3Dom component.
     *
     * @param component Xpp3Dom element
     */
    private void removeOldDependencies( Xpp3Dom component )
    {
        Xpp3Dom[] children = component.getChildren();
        for ( int i = children.length - 1; i >= 0; i-- )
        {
            Xpp3Dom child = children[i];
            if ( "orderEntry".equals( child.getName() ) && "module-library".equals( child.getAttribute( "type" ) ) )
            {
                component.removeChild( i );
            }
        }
    }

    private boolean isReactorProject( String groupId, String artifactId )
    {
        if ( reactorProjects != null )
        {
            for ( Iterator j = reactorProjects.iterator(); j.hasNext(); )
            {
                MavenProject p = (MavenProject) j.next();
                if ( p.getGroupId().equals( groupId ) && p.getArtifactId().equals( artifactId ) )
                {
                    return true;
                }
            }
        }
        return false;
    }

    private void resolveClassifier( Xpp3Dom element, Artifact a, String classifier )
    {
        String id = a.getId() + '-' + classifier;

        String path;
        if ( attemptedDownloads.containsKey( id ) )
        {
            getLog().debug( id + " was already downloaded." );
            path = (String) attemptedDownloads.get( id );
        }
        else
        {
            getLog().debug( id + " was not attempted to be downloaded yet: trying..." );
            path = resolveClassifiedArtifact( a, classifier );
            attemptedDownloads.put( id, path );
        }

        if ( path != null )
        {
            String jarPath = "jar://" + path + "!/";
            getLog().debug( "Setting " + classifier + " for " + id + " to " + jarPath );
            createElement( element, "root" ).setAttribute( "url", jarPath );
        }
    }

    private String resolveClassifiedArtifact( Artifact artifact, String classifier )
    {
        String basePath = artifact.getFile().getAbsolutePath().replace( '\\', '/' );
        int delIndex = basePath.indexOf( ".jar" );
        if ( delIndex < 0 )
        {
            return null;
        }

        List remoteRepos = project.getRemoteArtifactRepositories();
        try
        {
            Artifact classifiedArtifact = artifactFactory.createArtifactWithClassifier( artifact.getGroupId(),
                                                                                        artifact.getArtifactId(),
                                                                                        artifact.getVersion(),
                                                                                        artifact.getType(),
                                                                                        classifier );
            String dstFilename = basePath.substring( 0, delIndex ) + '-' + classifier + ".jar";
            File dstFile = new File( dstFilename );
            classifiedArtifact.setFile( dstFile );
            //this check is here because wagonManager does not seem to check if the remote file is newer
            //    or such feature is not working
            if ( !dstFile.exists() )
            {
                wagonManager.getArtifact( classifiedArtifact, remoteRepos );
            }
            return dstFile.getAbsolutePath().replace( '\\', '/' );
        }
        catch ( TransferFailedException e )
        {
            getLog().debug( e );
            return null;
        }
        catch ( ResourceDoesNotExistException e )
        {
            getLog().debug( e );
            return null;
        }
    }

    private void addResources( Xpp3Dom component, String directory )
    {
        Xpp3Dom dep = createElement( component, "orderEntry" );
        dep.setAttribute( "type", "module-library" );
        dep = createElement( dep, "library" );
        dep.setAttribute( "name", "resources" );

        Xpp3Dom el = createElement( dep, "CLASSES" );
        el = createElement( el, "root" );
        el.setAttribute( "url", getModuleFileUrl( directory ) );

        createElement( dep, "JAVADOC" );
        createElement( dep, "SOURCES" );
    }

    /**
     * Returns a an Xpp3Dom element (setting).
     *
     * @param component Xpp3Dom element
     * @param name      Setting attribute to find
     * @return setting Xpp3Dom element
     */
    private Xpp3Dom findSetting( Xpp3Dom component, String name )
    {
        Xpp3Dom[] settings = component.getChildren( "setting" );
        for ( int i = 0; i < settings.length; i++ )
        {
            if ( name.equals( settings[i].getAttribute( "name" ) ) )
            {
                return settings[i];
            }
        }

        Xpp3Dom setting = createElement( component, "setting" );
        setting.setAttribute( "name", name );
        return setting;
    }
}
