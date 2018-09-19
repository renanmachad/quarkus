package org.jboss.shamrock.undertow.runtime;

import java.util.EventListener;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.MultipartConfigElement;
import javax.servlet.Servlet;
import javax.servlet.ServletException;

import org.jboss.shamrock.runtime.ContextObject;
import org.jboss.shamrock.runtime.InjectionInstance;
import org.jboss.shamrock.runtime.StartupContext;

import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.CanonicalPathHandler;
import io.undertow.server.handlers.resource.ClassPathResourceManager;
import io.undertow.servlet.Servlets;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.DeploymentManager;
import io.undertow.servlet.api.FilterInfo;
import io.undertow.servlet.api.InstanceFactory;
import io.undertow.servlet.api.ListenerInfo;
import io.undertow.servlet.api.ServletContainer;
import io.undertow.servlet.api.ServletInfo;

/**
 * Provides the runtime methods to bootstrap Undertow. This class is present in the final uber-jar,
 * and is invoked from generated bytecode
 */
public class UndertowDeploymentTemplate {

    public static final HttpHandler ROOT_HANDLER = new HttpHandler() {
        @Override
        public void handleRequest(HttpServerExchange exchange) throws Exception {
            currentRoot.handleRequest(exchange);
        }
    };

    private static volatile Undertow undertow;
    private static volatile HttpHandler currentRoot;

    @ContextObject("deploymentInfo")
    public DeploymentInfo createDeployment(String name) {
        DeploymentInfo d = new DeploymentInfo();
        d.setClassLoader(getClass().getClassLoader());
        d.setDeploymentName(name);
        d.setContextPath("/");
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = new ClassLoader() {
            };
        }
        d.setClassLoader(cl);
        d.setResourceManager(new ClassPathResourceManager(d.getClassLoader(), "META-INF/resources"));
        return d;
    }

    public <T> InstanceFactory<T> createInstanceFactory(InjectionInstance<T> injectionInstance) {
        return new ShamrockInstanceFactory<T>(injectionInstance);
    }

    public AtomicReference<ServletInfo> registerServlet(@ContextObject("deploymentInfo") DeploymentInfo info,
                                                        String name,
                                                        Class<?> servletClass,
                                                        boolean asyncSupported,
                                                        int loadOnStartup,
                                                        InstanceFactory<? extends Servlet> instanceFactory) throws Exception {
        ServletInfo servletInfo = new ServletInfo(name, (Class<? extends Servlet>) servletClass, instanceFactory);
        info.addServlet(servletInfo);
        servletInfo.setAsyncSupported(asyncSupported);
        if (loadOnStartup > 0) {
            servletInfo.setLoadOnStartup(loadOnStartup);
        }
        return new AtomicReference<>(servletInfo);
    }

    public void addServletInitParam(AtomicReference<ServletInfo> info, String name, String value) {
        info.get().addInitParam(name, value);
    }

    public void addServletMapping(@ContextObject("deploymentInfo") DeploymentInfo info, String name, String mapping) throws Exception {
        ServletInfo sv = info.getServlets().get(name);
        sv.addMapping(mapping);
    }

    public void setMultipartConfig(AtomicReference<ServletInfo> sref, String location, long fileSize, long maxRequestSize, int fileSizeThreshold) {
        MultipartConfigElement mp = new MultipartConfigElement(location, fileSize, maxRequestSize, fileSizeThreshold);
        sref.get().setMultipartConfig(mp);
    }

    public AtomicReference<FilterInfo> registerFilter(@ContextObject("deploymentInfo") DeploymentInfo info,
                                                      String name, Class<?> filterClass,
                                                      boolean asyncSupported,
                                                      InstanceFactory<? extends Filter> instanceFactory) throws Exception {
        FilterInfo filterInfo = new FilterInfo(name, (Class<? extends Filter>) filterClass, instanceFactory);
        info.addFilter(filterInfo);
        filterInfo.setAsyncSupported(asyncSupported);
        return new AtomicReference<>(filterInfo);
    }

    public void addFilterInitParam(AtomicReference<FilterInfo> info, String name, String value) {
        info.get().addInitParam(name, value);
    }

    public void addFilterMapping(@ContextObject("deploymentInfo") DeploymentInfo info, String name, String mapping, DispatcherType dispatcherType) throws Exception {
        info.addFilterUrlMapping(name, mapping, dispatcherType);
    }


    public void registerListener(@ContextObject("deploymentInfo") DeploymentInfo info, Class<?> listenerClass, InstanceFactory<? extends EventListener> factory) {
        info.addListener(new ListenerInfo((Class<? extends EventListener>)listenerClass, factory));
    }

    public void addServletContextParameter(@ContextObject("deploymentInfo") DeploymentInfo info, String name, String value) {
        info.addInitParameter(name, value);
    }

    public void startUndertow(StartupContext startupContext, @ContextObject("servletHandler") HttpHandler handler, String port) throws ServletException {
        if (undertow == null) {
            undertow = Undertow.builder()
                    .addHttpListener(Integer.parseInt(port), "localhost")
                    .setHandler(new CanonicalPathHandler(ROOT_HANDLER))
                    .build();
            undertow.start();
        }
        currentRoot = handler;
//        startupContext.addCloseable(new Closeable() {
//            @Override
//            public void close() throws IOException {
//                val.stop();
//            }
//        });
    }

    @ContextObject("servletHandler")
    public HttpHandler bootServletContainer(@ContextObject("deploymentInfo") DeploymentInfo info) {
        try {
            ServletContainer servletContainer = Servlets.defaultContainer();
            DeploymentManager manager = servletContainer.addDeployment(info);
            manager.deploy();
            return manager.start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
