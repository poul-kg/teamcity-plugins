package com.goldin.plugins.teamcity.console

import jetbrains.buildServer.controllers.BaseController
import jetbrains.buildServer.serverSide.SBuildServer
import jetbrains.buildServer.web.openapi.WebControllerManager
import jetbrains.buildServer.web.util.SessionUser
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.customizers.SecureASTCustomizer
import org.springframework.context.ApplicationContext
import org.springframework.web.servlet.ModelAndView

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse


/**
 * Reads code evaluation ajax request and returns the evaluation result.
 */
class CodeEvalController extends BaseController
{
    static final String MAPPING = '/consoleCodeEval.html'

    private final ApplicationContext context


    /**
     * Classes that are not allowed to be used when evaluating the code.
     */
    private final Set<Class>            forbiddenClasses      = [ System, Runtime, Class, ClassLoader, URLClassLoader ] as Set
    private final Set<String>           forbiddenMethods      = [ 'getClassLoader', 'loadClass', 'forName' ] as Set
    private final Set<String>           forbiddenProperties   = [ 'classLoader' ] as Set
    private final Set<String>           forbiddenConstants    = forbiddenClasses*.name
    private final CompilerConfiguration compilerConfiguration = compilerConfiguration( forbiddenClasses,
                                                                                       forbiddenMethods,
                                                                                       forbiddenProperties,
                                                                                       forbiddenConstants )
    CodeEvalController ( SBuildServer         server,
                         WebControllerManager manager,
                         ApplicationContext   context )
    {
        super( server )

        manager.registerController( MAPPING, this )
        this.context = context
    }


    @Override
    protected ModelAndView doHandle ( HttpServletRequest  request,
                                      HttpServletResponse response )
    {
        String code = request.getParameter( 'code' )?.trim()

        if ( code && SessionUser.getUser( request )?.systemAdministratorRoleGranted )
        {
            code = code.readLines()*.trim().grep().findAll{ ! it.startsWith( '#' ) }.join( '\n' )

            if ( code )
            {
                final responseBytes    = ( getValue( request, code )?.toString() ?: 'null' ).getBytes( 'UTF-8' )
                response.contentLength = responseBytes.size()
                response.contentType   = 'Content-Type: text/plain; charset=utf-8'
                response.outputStream.write( responseBytes )
                response.flushBuffer()
            }
        }

        null
    }


    /**
     * Evaluates expression specified and returns the result.
     *
     * @param request    current HTTP request to pass to binding
     * @param expression expression to evaluate
     * @return           evaluation result or stack trace as a String
     */
    @SuppressWarnings([ 'CatchThrowable' ])
    private Object getValue( HttpServletRequest request, String expression )
    {
        assert request && expression

        try
        {
            new GroovyShell( new Binding([ request : request,
                                           context : context,
                                           server  : myServer,
                                           c       : this.&loadClass,
                                           b       : this.&loadBean ]), compilerConfiguration ).
            evaluate( expression )
        }
        catch ( Throwable t )
        {
            final writer = new StringWriter()
            t.printStackTrace( new PrintWriter( writer ))
            writer.toString()
        }
    }


    /**
     * {@link ClassLoader#loadClass(java.lang.String)} convenience wrapper, provided as 'c' method in the code console.
     *
     * @param className name of the class to load, can omit 'jetbrains.buildServer.' or use 'j.b.' instead
     * @return class loaded
     * @throws ClassNotFoundException if no attempts resulted in class loaded successfully
     */
    private Class loadClass ( String className )
    {
        assert className

        for ( name in [ className,
                        'jetbrains.buildServer.' + className,
                        'jetbrains.buildServer.' + className.replace( 'j.b.', '' ) ])
        {
            final c  = tryIt { this.class.classLoader.loadClass( name ) } as Class
            if  ( c != null ){ return c }
        }

        throw new ClassNotFoundException( className )
    }


    /**
     * {@link ApplicationContext#getBean} convenience wrapper, provided as 'b' method in the code console.
     * @param o object to use for retrieving a bean, should be {@link String} or {@link Class}
     * @return single bean, beans list or beans maps found
     */
    private Object loadBean( Object o )
    {
        final beans        = [] as Set // Single beans
        final beansOfType  = [:]       // "Beans of type X", mapping from bean name to bean instances
        final loadBeanFrom = {
            ApplicationContext c ->

            final result = loadBean( o, c )
            if (( ! ( result instanceof Map )) && ( result != null ))    { beans       << result }
            if (( result instanceof Map      ) && ( ! result.isEmpty())) { beansOfType << result }
        }

        assert context.parent.parent.parent == null // There are only 3 contexts: plugin's and its two parents

        loadBeanFrom( context )
        loadBeanFrom( context.parent )
        loadBeanFrom( context.parent.parent )

        return ( beans && ( ! beansOfType )) ? ( beans.size() == 1 ? beans.toList().first() : beans ) :
               (( ! beans ) && beansOfType ) ? beansOfType :
                                               beans + beansOfType // List of beans plus a Map element
    }


    /**
     * Attempts to load a bean from the context specified.
     *
     * @param o object to use for retrieving a bean, should be {@link String} or {@link Class}
     * @param c context to use for for retrieving a bean
     * @return bean located or mapping of beans returned by {@link ApplicationContext#getBeansOfType}
     */
    private Object loadBean( Object o, ApplicationContext c )
    {
        assert (( o instanceof String ) || ( o instanceof Class )) && c

        if ( o instanceof String )
        {
            tryIt { c.getBean(( String ) o )}
        }
        else if ( o instanceof Class )
        {
            tryIt( c.getBeansOfType(( Class ) o )){ c.getBean(( Class ) o )}
        }
        else
        {
            throw new RuntimeException(
                "Object of type [${ o.class.name }] specified should be of type 'String' or 'Class'" )
        }
    }


    /**
     * Creates {@link CompilerConfiguration} instance secured from running any of {@link #forbiddenClasses}.
     *
     * @param forbiddenClasses    fully-qualified name of classes that are not allowed to be used
     * @param forbiddenMethods    name of methods that are not allowed to be used
     * @param forbiddenProperties name of object properties that are not allowed to be used
     * @param forbiddenConstants  name of constants that are not allowed to be used
     * @return {@link CompilerConfiguration} instance secured from running forbidden code
     */
    private CompilerConfiguration compilerConfiguration( Set<Class>  forbiddenClasses,
                                                         Set<String> forbiddenMethods,
                                                         Set<String> forbiddenProperties,
                                                         Set<String> forbiddenConstants )
    {
        final is         = { Closure c -> tryIt( false, c )}
        final customizer = new SecureASTCustomizer()
        customizer.addExpressionCheckers({
            Expression e ->

            boolean forbiddenCall = is { e.objectExpression.type.name in forbiddenClasses*.name } ||
                                    is { e.methodAsString             in forbiddenMethods       } ||
                                    is { e.propertyAsString           in forbiddenProperties    } ||
                                    is { e.value                      in forbiddenConstants     }
            ( ! forbiddenCall )

        } as SecureASTCustomizer.ExpressionChecker )

        /**
         * Various "attacks" to check:
         * System.gc()
         * System."${ 'gc' }"()
         * System.methods.find{ it.name == 'gc' }.invoke( null )
         * Class.forName( 'java.lang.System' )."${ 'gc' }"()
         * this.class.classLoader.loadClass( 'java.lang.System' )."${ 'gc' }"()
         */

        final config = new CompilerConfiguration()
        config.addCompilationCustomizers( customizer )

        config
    }


    /**
     * Attempts to invoke the closure specified and return its value.
     *
     * @param   defaultValue default value to return if closure invocation throws an Exception
     * @param c closure to invoke
     * @return  closure return value or default value
     */
    private tryIt( Object defaultValue = null, Closure c )
    {
        assert c
        try { c() } catch ( ignored ) { defaultValue }
    }
}