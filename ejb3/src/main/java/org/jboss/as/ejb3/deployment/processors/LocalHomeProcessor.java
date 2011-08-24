/*
 * JBoss, Home of Professional Open Source
 * Copyright 2010, Red Hat Inc., and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
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
package org.jboss.as.ejb3.deployment.processors;

import org.jboss.as.ee.component.ComponentConfiguration;
import org.jboss.as.ee.component.ComponentDescription;
import org.jboss.as.ee.component.ComponentStartService;
import org.jboss.as.ee.component.ComponentView;
import org.jboss.as.ee.component.DependencyConfigurator;
import org.jboss.as.ee.component.ViewConfiguration;
import org.jboss.as.ee.component.ViewConfigurator;
import org.jboss.as.ee.component.ViewDescription;
import org.jboss.as.ee.component.deployers.AbstractComponentConfigProcessor;
import org.jboss.as.ee.component.interceptors.InterceptorOrder;
import org.jboss.as.ejb3.component.EJBViewDescription;
import org.jboss.as.ejb3.component.MethodIntf;
import org.jboss.as.ejb3.component.session.SessionBeanComponentDescription;
import org.jboss.as.ejb3.component.stateless.StatelessComponentDescription;
import org.jboss.as.ejb3.component.stateless.StatelessSessionHomeInterceptorFactory;
import org.jboss.as.server.deployment.DeploymentPhaseContext;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUnitProcessingException;
import org.jboss.as.server.deployment.annotation.CompositeIndex;
import org.jboss.as.server.deployment.reflect.ClassReflectionIndex;
import org.jboss.as.server.deployment.reflect.DeploymentReflectionIndex;
import org.jboss.msc.service.ServiceBuilder;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * Processor that hooks up local home interfaces. At the moment it only hooks up stateless session beans
 *
 * <p/>
 * Not sure if this is the best place for this code at the moment.
 *
 * @author Stuart Douglas
 */
public class LocalHomeProcessor extends AbstractComponentConfigProcessor {

    @Override
    protected void processComponentConfig(final DeploymentUnit deploymentUnit, final DeploymentPhaseContext phaseContext, final CompositeIndex index, final ComponentDescription componentDescription) throws DeploymentUnitProcessingException {

        if (componentDescription instanceof StatelessComponentDescription) {
            final StatelessComponentDescription ejbComponentDescription = (StatelessComponentDescription) componentDescription;

            //check for EJB's with a local home interface
            if (ejbComponentDescription.getEjbLocalHomeView() != null) {
                final EJBViewDescription view = ejbComponentDescription.getEjbLocalHomeView();
                view.getConfigurators().add(new ViewConfigurator() {
                    @Override
                    public void configure(final DeploymentPhaseContext context, final ComponentConfiguration componentConfiguration, final ViewDescription description, final ViewConfiguration configuration) throws DeploymentUnitProcessingException {
                        final DeploymentReflectionIndex reflectionIndex = phaseContext.getDeploymentUnit().getAttachment(org.jboss.as.server.deployment.Attachments.REFLECTION_INDEX);

                        configuration.addClientPostConstructInterceptor(org.jboss.invocation.Interceptors.getTerminalInterceptorFactory(), InterceptorOrder.ClientPostConstruct.TERMINAL_INTERCEPTOR);
                        configuration.addClientPreDestroyInterceptor(org.jboss.invocation.Interceptors.getTerminalInterceptorFactory(), InterceptorOrder.ClientPreDestroy.TERMINAL_INTERCEPTOR);

                        configuration.addViewPostConstructInterceptor(org.jboss.invocation.Interceptors.getTerminalInterceptorFactory(), InterceptorOrder.ViewPostConstruct.TERMINAL_INTERCEPTOR);
                        configuration.addViewPreDestroyInterceptor(org.jboss.invocation.Interceptors.getTerminalInterceptorFactory(), InterceptorOrder.ViewPreDestroy.TERMINAL_INTERCEPTOR);

                        //loop over methods looking for create methods:
                        final ClassReflectionIndex<?> classIndex = reflectionIndex.getClassIndex(configuration.getViewClass());
                        for (Method method : classIndex.getMethods()) {
                            if(method.getName().startsWith("create")) {
                                //we have a create method
                                final ViewDescription createdView = resolveViewDescription(method, ejbComponentDescription);
                                final StatelessSessionHomeInterceptorFactory factory = new StatelessSessionHomeInterceptorFactory();

                                //add a dependency on the view to create
                                componentConfiguration.getStartDependencies().add(new DependencyConfigurator<ComponentStartService>() {
                                    @Override
                                    public void configureDependency(final ServiceBuilder<?> serviceBuilder, final ComponentStartService service) throws DeploymentUnitProcessingException {
                                        serviceBuilder.addDependency(createdView.getServiceName(), ComponentView.class, factory.getViewToCreate());
                                    }
                                });

                                //add the interceptor
                                configuration.addClientInterceptor(method, factory, InterceptorOrder.View.COMPONENT_DISPATCHER);
                            }
                        }

                    }
                });
            }

        }
    }

    /**
     * Resolves the correct view for a create method
     */
    private ViewDescription resolveViewDescription(final Method method, final SessionBeanComponentDescription componentDescription) throws DeploymentUnitProcessingException {
        if(componentDescription.getEjbLocalView() != null) {
            return componentDescription.getEjbLocalView();
        }
        final Set<ViewDescription> local = new HashSet<ViewDescription>();
        for(final ViewDescription view : componentDescription.getViews()) {
            if(view instanceof EJBViewDescription) {
                final EJBViewDescription ejbView = (EJBViewDescription)view;
                if(ejbView.getMethodIntf() == MethodIntf.LOCAL) {
                    if(ejbView.getViewClassName().equals(method.getReturnType().getName())) {
                        return ejbView;
                    }
                    local.add(view);
                }
            }
        }
        if(local.size() == 1) {
            return local.iterator().next();
        }
        throw new DeploymentUnitProcessingException("Could not determine correct local view to create for EJB Home 'create' method " + method);
    }
}
