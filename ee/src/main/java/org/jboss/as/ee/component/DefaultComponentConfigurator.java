package org.jboss.as.ee.component;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

import org.jboss.as.ee.component.interceptors.InterceptorClassDescription;
import org.jboss.as.ee.component.interceptors.InterceptorOrder;
import org.jboss.as.ee.component.interceptors.UserInterceptorFactory;
import org.jboss.as.ee.metadata.MetadataCompleteMarker;
import org.jboss.as.server.deployment.DeploymentPhaseContext;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUnitProcessingException;
import org.jboss.as.server.deployment.reflect.ClassReflectionIndexUtil;
import org.jboss.as.server.deployment.reflect.DeploymentReflectionIndex;
import org.jboss.invocation.ContextClassLoaderInterceptor;
import org.jboss.invocation.ImmediateInterceptorFactory;
import org.jboss.invocation.InterceptorFactory;
import org.jboss.invocation.Interceptors;
import org.jboss.invocation.PrivilegedWithCombinerInterceptor;
import org.jboss.invocation.proxy.MethodIdentifier;
import org.jboss.modules.Module;

import static org.jboss.as.server.deployment.Attachments.REFLECTION_INDEX;

/**
 * @author Stuart Douglas
 */
class DefaultComponentConfigurator extends AbstractComponentConfigurator implements ComponentConfigurator {

    public void configure(final DeploymentPhaseContext context, final ComponentDescription description, final ComponentConfiguration configuration) throws DeploymentUnitProcessingException {
        final DeploymentUnit deploymentUnit = context.getDeploymentUnit();
        final DeploymentReflectionIndex deploymentReflectionIndex = deploymentUnit.getAttachment(REFLECTION_INDEX);
        final Module module = deploymentUnit.getAttachment(org.jboss.as.server.deployment.Attachments.MODULE);
        final EEApplicationClasses applicationClasses = deploymentUnit.getAttachment(Attachments.EE_APPLICATION_CLASSES_DESCRIPTION);
        final EEModuleDescription moduleDescription = deploymentUnit.getAttachment(Attachments.EE_MODULE_DESCRIPTION);

        final boolean metadataComplete = MetadataCompleteMarker.isMetadataComplete(deploymentUnit);

        // Module stuff

        final Deque<InterceptorFactory> injectors = new ArrayDeque<>();
        final Deque<InterceptorFactory> uninjectors = new ArrayDeque<>();
        final Deque<InterceptorFactory> destructors = new ArrayDeque<>();

        final List<InterceptorFactory> componentUserAroundInvoke = new ArrayList<>();
        final List<InterceptorFactory> componentUserAroundTimeout;
        final List<InterceptorFactory> userPostConstruct = new ArrayList<>();
        final List<InterceptorFactory> userPreDestroy = new ArrayList<>();
        final List<InterceptorFactory> componentUserPrePassivate;
        final List<InterceptorFactory> componentUserPostActivate;

        final Set<MethodIdentifier> timeoutMethods = description.getTimerMethods();
        if (description.isTimerServiceRequired()) {
            componentUserAroundTimeout = new ArrayList<>();
        } else {
            componentUserAroundTimeout = null;
        }


        if (description.isPassivationApplicable()) {
            componentUserPrePassivate = new ArrayList<>();
            componentUserPostActivate = new ArrayList<>();
        } else {
            componentUserPrePassivate = null;
            componentUserPostActivate = null;
        }


        destructors.addLast(new ImmediateInterceptorFactory(new ManagedReferenceReleaseInterceptor(BasicComponentInstance.INSTANCE_KEY)));

        new ClassDescriptionTraversal(configuration.getComponentClass(), applicationClasses) {
            @Override
            public void handle(Class<?> clazz, EEModuleClassDescription classDescription) throws DeploymentUnitProcessingException {
                mergeInjectionsForClass(clazz, configuration.getComponentClass(), classDescription, moduleDescription, deploymentReflectionIndex, description, configuration, context, injectors, BasicComponentInstance.INSTANCE_KEY, uninjectors, metadataComplete);
            }
        }.run();


        new ClassDescriptionTraversal(configuration.getComponentClass(), applicationClasses) {
            @Override
            public void handle(final Class<?> clazz, EEModuleClassDescription classDescription) throws DeploymentUnitProcessingException {

                final InterceptorClassDescription interceptorConfig = InterceptorClassDescription.merge(ComponentDescription.mergeInterceptorConfig(clazz, classDescription, description, metadataComplete), moduleDescription.getInterceptorClassOverride(clazz.getName()));

                handleClassMethod(clazz, interceptorConfig.getPostConstruct(), userPostConstruct, true, true);
                handleClassMethod(clazz, interceptorConfig.getPreDestroy(), userPreDestroy, true, true);
                handleClassMethod(clazz, interceptorConfig.getAroundInvoke(), componentUserAroundInvoke, false, false);

                if (description.isTimerServiceRequired()) {
                    handleClassMethod(clazz, interceptorConfig.getAroundTimeout(), componentUserAroundTimeout, false, false);
                }

                if (description.isPassivationApplicable()) {
                    handleClassMethod(clazz, interceptorConfig.getPrePassivate(), componentUserPrePassivate, false, false);
                    handleClassMethod(clazz, interceptorConfig.getPostActivate(), componentUserPostActivate, false, false);
                }
            }

            private void handleClassMethod(final Class<?> clazz, final MethodIdentifier methodIdentifier, final List<InterceptorFactory> interceptors, boolean changeMethod, boolean lifecycleMethod) throws DeploymentUnitProcessingException {
                if (methodIdentifier != null) {
                    final Method method = ClassReflectionIndexUtil.findRequiredMethod(deploymentReflectionIndex, clazz, methodIdentifier);
                    if (isNotOverriden(clazz, method, configuration.getComponentClass(), deploymentReflectionIndex)) {
                        InterceptorFactory interceptorFactory = new ImmediateInterceptorFactory(new ManagedReferenceLifecycleMethodInterceptor(BasicComponentInstance.INSTANCE_KEY, method, changeMethod, lifecycleMethod));
                        interceptors.add(interceptorFactory);
                    }
                }
            }
        }.run();

        final ClassLoader classLoader = module.getClassLoader();
        final InterceptorFactory tcclInterceptor = new ImmediateInterceptorFactory(new ContextClassLoaderInterceptor(classLoader));
        final InterceptorFactory privilegedInterceptor = PrivilegedWithCombinerInterceptor.getFactory();


        if (!injectors.isEmpty()) {
            configuration.addPostConstructInterceptor(weaved(injectors), InterceptorOrder.ComponentPostConstruct.COMPONENT_RESOURCE_INJECTION_INTERCEPTORS);
        }
        // Apply post-construct
        if (!userPostConstruct.isEmpty()) {
            configuration.addPostConstructInterceptor(weaved(userPostConstruct), InterceptorOrder.ComponentPostConstruct.COMPONENT_USER_INTERCEPTORS);
        }
        configuration.addPostConstructInterceptor(Interceptors.getTerminalInterceptorFactory(), InterceptorOrder.ComponentPostConstruct.TERMINAL_INTERCEPTOR);
        configuration.addPostConstructInterceptor(tcclInterceptor, InterceptorOrder.ComponentPostConstruct.TCCL_INTERCEPTOR);
        configuration.addPostConstructInterceptor(privilegedInterceptor, InterceptorOrder.ComponentPostConstruct.PRIVILEGED_INTERCEPTOR);

        // Apply pre-destroy
        if (!uninjectors.isEmpty()) {
            configuration.addPreDestroyInterceptor(weaved(uninjectors), InterceptorOrder.ComponentPreDestroy.COMPONENT_UNINJECTION_INTERCEPTORS);
        }
        if (!destructors.isEmpty()) {
            configuration.addPreDestroyInterceptor(weaved(destructors), InterceptorOrder.ComponentPreDestroy.COMPONENT_DESTRUCTION_INTERCEPTORS);
        }
        if (!userPreDestroy.isEmpty()) {
            configuration.addPreDestroyInterceptor(weaved(userPreDestroy), InterceptorOrder.ComponentPreDestroy.COMPONENT_USER_INTERCEPTORS);
        }
        configuration.addPreDestroyInterceptor(Interceptors.getTerminalInterceptorFactory(), InterceptorOrder.ComponentPreDestroy.TERMINAL_INTERCEPTOR);
        configuration.addPreDestroyInterceptor(tcclInterceptor, InterceptorOrder.ComponentPreDestroy.TCCL_INTERCEPTOR);
        configuration.addPreDestroyInterceptor(privilegedInterceptor, InterceptorOrder.ComponentPreDestroy.PRIVILEGED_INTERCEPTOR);

        if (description.isPassivationApplicable()) {
            if (!componentUserPrePassivate.isEmpty()) {
                configuration.addPrePassivateInterceptor(weaved(componentUserPrePassivate), InterceptorOrder.ComponentPassivation.COMPONENT_USER_INTERCEPTORS);
            }
            configuration.addPrePassivateInterceptor(Interceptors.getTerminalInterceptorFactory(), InterceptorOrder.ComponentPassivation.TERMINAL_INTERCEPTOR);
            configuration.addPrePassivateInterceptor(tcclInterceptor, InterceptorOrder.ComponentPassivation.TCCL_INTERCEPTOR);
            configuration.addPrePassivateInterceptor(privilegedInterceptor, InterceptorOrder.ComponentPassivation.PRIVILEGED_INTERCEPTOR);
            if (!componentUserPostActivate.isEmpty()) {
                configuration.addPostActivateInterceptor(weaved(componentUserPostActivate), InterceptorOrder.ComponentPassivation.COMPONENT_USER_INTERCEPTORS);
            }
            configuration.addPostActivateInterceptor(Interceptors.getTerminalInterceptorFactory(), InterceptorOrder.ComponentPassivation.TERMINAL_INTERCEPTOR);
            configuration.addPostActivateInterceptor(tcclInterceptor, InterceptorOrder.ComponentPassivation.TCCL_INTERCEPTOR);
            configuration.addPostActivateInterceptor(privilegedInterceptor, InterceptorOrder.ComponentPassivation.PRIVILEGED_INTERCEPTOR);
        }

        // @AroundInvoke interceptors
        if (description.isIntercepted()) {

            for (final Method method : configuration.getDefinedComponentMethods()) {

                //now add the interceptor that initializes and the interceptor that actually invokes to the end of the interceptor chain

                configuration.addComponentInterceptor(method, Interceptors.getInitialInterceptorFactory(), InterceptorOrder.Component.INITIAL_INTERCEPTOR);
                configuration.addComponentInterceptor(method, new ImmediateInterceptorFactory(new ManagedReferenceMethodInterceptor(BasicComponentInstance.INSTANCE_KEY, method)), InterceptorOrder.Component.TERMINAL_INTERCEPTOR);

                final MethodIdentifier identifier = MethodIdentifier.getIdentifier(method.getReturnType(), method.getName(), method.getParameterTypes());

                // first add the default interceptors (if not excluded) to the deque
                final boolean requiresTimerChain = description.isTimerServiceRequired() && timeoutMethods.contains(identifier);

                configuration.addComponentInterceptor(method, new UserInterceptorFactory(weaved(componentUserAroundInvoke), weaved(requiresTimerChain ? componentUserAroundTimeout : null)), InterceptorOrder.Component.COMPONENT_USER_INTERCEPTORS);
            }
        }

    }
}
