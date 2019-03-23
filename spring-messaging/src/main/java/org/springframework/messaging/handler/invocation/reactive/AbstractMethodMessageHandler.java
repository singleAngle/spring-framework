/*
 * Copyright 2002-2019 the original author or authors.
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

package org.springframework.messaging.handler.invocation.reactive;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.ReactiveAdapterRegistry;
import org.springframework.lang.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.ReactiveMessageHandler;
import org.springframework.messaging.handler.HandlerMethod;
import org.springframework.messaging.handler.MessagingAdviceBean;
import org.springframework.messaging.handler.invocation.AbstractExceptionHandlerMethodResolver;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.ObjectUtils;

/**
 * Abstract base class for reactive HandlerMethod-based message handling.
 * Provides most of the logic required to discover handler methods at startup,
 * find a matching handler method at runtime for a given message and invoke it.
 *
 * <p>Also supports discovering and invoking exception handling methods to process
 * exceptions raised during message handling.
 *
 * @author Rossen Stoyanchev
 * @since 5.2
 * @param <T> the type of the Object that contains information mapping information
 */
public abstract class AbstractMethodMessageHandler<T>
		implements ReactiveMessageHandler, ApplicationContextAware, InitializingBean, BeanNameAware {

	/**
	 * Bean name prefix for target beans behind scoped proxies. Used to exclude those
	 * targets from handler method detection, in favor of the corresponding proxies.
	 * <p>We're not checking the autowire-candidate status here, which is how the
	 * proxy target filtering problem is being handled at the autowiring level,
	 * since autowire-candidate may have been turned to {@code false} for other
	 * reasons, while still expecting the bean to be eligible for handler methods.
	 * <p>Originally defined in {@link org.springframework.aop.scope.ScopedProxyUtils}
	 * but duplicated here to avoid a hard dependency on the spring-aop module.
	 */
	private static final String SCOPED_TARGET_NAME_PREFIX = "scopedTarget.";


	protected final Log logger = LogFactory.getLog(getClass());


	private ArgumentResolverConfigurer argumentResolverConfigurer = new ArgumentResolverConfigurer();

	private ReturnValueHandlerConfigurer returnValueHandlerConfigurer = new ReturnValueHandlerConfigurer();

	private final InvocableHelper invocableHelper = new InvocableHelper(this::createExceptionMethodResolverFor);

	@Nullable
	private ApplicationContext applicationContext;

	@Nullable
	private String beanName;

	private final Map<T, HandlerMethod> handlerMethods = new LinkedHashMap<>(64);

	private final MultiValueMap<String, T> destinationLookup = new LinkedMultiValueMap<>(64);


	/**
	 * Configure custom resolvers for handler method arguments.
	 */
	public void setArgumentResolverConfigurer(ArgumentResolverConfigurer configurer) {
		Assert.notNull(configurer, "HandlerMethodArgumentResolver is required");
		this.argumentResolverConfigurer = configurer;
	}

	/**
	 * Return the configured custom resolvers for handler method arguments.
	 */
	public ArgumentResolverConfigurer getArgumentResolverConfigurer() {
		return this.argumentResolverConfigurer;
	}

	/**
	 * Configure custom return value handlers for handler metohds.
	 */
	public void setReturnValueHandlerConfigurer(ReturnValueHandlerConfigurer configurer) {
		Assert.notNull(configurer, "ReturnValueHandlerConfigurer is required");
		this.returnValueHandlerConfigurer = configurer;
	}

	/**
	 * Return the configured return value handlers.
	 */
	public ReturnValueHandlerConfigurer getReturnValueHandlerConfigurer() {
		return this.returnValueHandlerConfigurer;
	}

	/**
	 * Configure the registry for adapting various reactive types.
	 * <p>By default this is an instance of {@link ReactiveAdapterRegistry} with
	 * default settings.
	 */
	public void setReactiveAdapterRegistry(ReactiveAdapterRegistry registry) {
		this.invocableHelper.setReactiveAdapterRegistry(registry);
	}

	/**
	 * Return the configured registry for adapting reactive types.
	 */
	public ReactiveAdapterRegistry getReactiveAdapterRegistry() {
		return this.invocableHelper.getReactiveAdapterRegistry();
	}

	@Override
	public void setApplicationContext(@Nullable ApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
	}

	@Nullable
	public ApplicationContext getApplicationContext() {
		return this.applicationContext;
	}

	@Override
	public void setBeanName(String name) {
		this.beanName = name;
	}

	public String getBeanName() {
		return this.beanName != null ? this.beanName :
				getClass().getSimpleName() + "@" + ObjectUtils.getIdentityHexString(this);
	}

	/**
	 * Subclasses can invoke this method to populate the MessagingAdviceBean cache
	 * (e.g. to support "global" {@code @MessageExceptionHandler}).
	 */
	protected void registerExceptionHandlerAdvice(
			MessagingAdviceBean bean, AbstractExceptionHandlerMethodResolver resolver) {

		this.invocableHelper.registerExceptionHandlerAdvice(bean, resolver);
	}

	/**
	 * Return a read-only map with all handler methods and their mappings.
	 */
	public Map<T, HandlerMethod> getHandlerMethods() {
		return Collections.unmodifiableMap(this.handlerMethods);
	}

	/**
	 * Return a read-only multi-value map with a direct lookup of mappings,
	 * (e.g. for non-pattern destinations).
	 */
	public MultiValueMap<String, T> getDestinationLookup() {
		return CollectionUtils.unmodifiableMultiValueMap(this.destinationLookup);
	}


	@Override
	public void afterPropertiesSet() {

		List<? extends HandlerMethodArgumentResolver> resolvers = initArgumentResolvers();
		if (resolvers.isEmpty()) {
			resolvers = new ArrayList<>(this.argumentResolverConfigurer.getCustomResolvers());
		}
		this.invocableHelper.addArgumentResolvers(resolvers);

		List<? extends HandlerMethodReturnValueHandler> handlers = initReturnValueHandlers();
		if (handlers.isEmpty()) {
			handlers = new ArrayList<>(this.returnValueHandlerConfigurer.getCustomHandlers());
		}
		this.invocableHelper.addReturnValueHandlers(handlers);

		initHandlerMethods();
	}

	/**
	 * Return the list of argument resolvers to use.
	 * <p>Subclasses should also take into account custom argument types configured via
	 * {@link #setArgumentResolverConfigurer}.
	 */
	protected abstract List<? extends HandlerMethodArgumentResolver> initArgumentResolvers();

	/**
	 * Return the list of return value handlers to use.
	 * <p>Subclasses should also take into account custom return value types configured
	 * via {@link #setReturnValueHandlerConfigurer}.
	 */
	protected abstract List<? extends HandlerMethodReturnValueHandler> initReturnValueHandlers();


	private void initHandlerMethods() {
		if (this.applicationContext == null) {
			logger.warn("No ApplicationContext available for detecting beans with message handling methods.");
			return;
		}
		Predicate<Class<?>> handlerPredicate = initHandlerPredicate();
		if (handlerPredicate == null) {
			logger.warn("[" + getBeanName() + "] No auto-detection of handler methods (e.g. in @Controller).");
			return;
		}
		for (String beanName : this.applicationContext.getBeanNamesForType(Object.class)) {
			if (!beanName.startsWith(SCOPED_TARGET_NAME_PREFIX)) {
				Class<?> beanType = null;
				try {
					beanType = this.applicationContext.getType(beanName);
				}
				catch (Throwable ex) {
					// An unresolvable bean type, probably from a lazy bean - let's ignore it.
					if (logger.isDebugEnabled()) {
						logger.debug("Could not resolve target class for bean with name '" + beanName + "'", ex);
					}
				}
				if (beanType != null && handlerPredicate.test(beanType)) {
					detectHandlerMethods(beanName);
				}
			}
		}
	}

	/**
	 * Return the predicate to use to check whether a given Spring bean should
	 * be introspected for message handling methods. If {@code null} is
	 * returned, auto-detection is effectively disabled.
	 */
	@Nullable
	protected abstract Predicate<Class<?>> initHandlerPredicate();

	/**
	 * Detect if the given handler has any methods that can handle messages and if
	 * so register it with the extracted mapping information.
	 * <p><strong>Note:</strong> This method is protected and can be invoked by
	 * sub-classes, but this should be done on startup only as documented in
	 * {@link #registerHandlerMethod}.
	 * @param handler the handler to check, either an instance of a Spring bean name
	 */
	protected final void detectHandlerMethods(Object handler) {
		Class<?> handlerType;
		if (handler instanceof String) {
			ApplicationContext context = getApplicationContext();
			Assert.state(context != null, "ApplicationContext is required for resolving handler bean names");
			handlerType = context.getType((String) handler);
		}
		else {
			handlerType = handler.getClass();
		}
		if (handlerType != null) {
			final Class<?> userType = ClassUtils.getUserClass(handlerType);
			Map<Method, T> methods = MethodIntrospector.selectMethods(userType,
					(MethodIntrospector.MetadataLookup<T>) method -> getMappingForMethod(method, userType));
			if (logger.isDebugEnabled()) {
				logger.debug(formatMappings(userType, methods));
			}
			methods.forEach((key, value) -> registerHandlerMethod(handler, key, value));
		}
	}

	private String formatMappings(Class<?> userType, Map<Method, T> methods) {
		String formattedType = Arrays.stream(ClassUtils.getPackageName(userType).split("\\."))
				.map(p -> p.substring(0, 1))
				.collect(Collectors.joining(".", "", "." + userType.getSimpleName()));
		Function<Method, String> methodFormatter = method -> Arrays.stream(method.getParameterTypes())
				.map(Class::getSimpleName)
				.collect(Collectors.joining(",", "(", ")"));
		return methods.entrySet().stream()
				.map(e -> {
					Method method = e.getKey();
					return e.getValue() + ": " + method.getName() + methodFormatter.apply(method);
				})
				.collect(Collectors.joining("\n\t", "\n\t" + formattedType + ":" + "\n\t", ""));
	}

	/**
	 * Obtain the mapping for the given method, if any.
	 * @param method the method to check
	 * @param handlerType the handler type, possibly a sub-type of the method's declaring class
	 * @return the mapping, or {@code null} if the method is not mapped
	 */
	@Nullable
	protected abstract T getMappingForMethod(Method method, Class<?> handlerType);

	/**
	 * Register a handler method and its unique mapping.
	 * <p><strong>Note:</strong> This method is protected and can be invoked by
	 * sub-classes. Keep in mind however that the registration is not protected
	 * for concurrent use, and is expected to be done on startup.
	 * @param handler the bean name of the handler or the handler instance
	 * @param method the method to register
	 * @param mapping the mapping conditions associated with the handler method
	 * @throws IllegalStateException if another method was already registered
	 * under the same mapping
	 */
	protected final void registerHandlerMethod(Object handler, Method method, T mapping) {
		Assert.notNull(mapping, "Mapping must not be null");
		HandlerMethod newHandlerMethod = createHandlerMethod(handler, method);
		HandlerMethod oldHandlerMethod = this.handlerMethods.get(mapping);

		if (oldHandlerMethod != null && !oldHandlerMethod.equals(newHandlerMethod)) {
			throw new IllegalStateException("Ambiguous mapping found. Cannot map '" + newHandlerMethod.getBean() +
					"' bean method \n" + newHandlerMethod + "\nto " + mapping + ": There is already '" +
					oldHandlerMethod.getBean() + "' bean method\n" + oldHandlerMethod + " mapped.");
		}

		this.handlerMethods.put(mapping, newHandlerMethod);

		for (String pattern : getDirectLookupMappings(mapping)) {
			this.destinationLookup.add(pattern, mapping);
		}
	}

	/**
	 * Create a HandlerMethod instance from an Object handler that is either a handler
	 * instance or a String-based bean name.
	 */
	private HandlerMethod createHandlerMethod(Object handler, Method method) {
		HandlerMethod handlerMethod;
		if (handler instanceof String) {
			ApplicationContext context = getApplicationContext();
			Assert.state(context != null, "ApplicationContext is required for resolving handler bean names");
			String beanName = (String) handler;
			handlerMethod = new HandlerMethod(beanName, context.getAutowireCapableBeanFactory(), method);
		}
		else {
			handlerMethod = new HandlerMethod(handler, method);
		}
		return handlerMethod;
	}

	/**
	 * Return String-based destinations for the given mapping, if any, that can
	 * be used to find matches with a direct lookup (i.e. non-patterns).
	 * <p><strong>Note:</strong> This is completely optional. The mapping
	 * metadata for a sub-class may support neither direct lookups, nor String
	 * based destinations.
	 */
	protected abstract Set<String> getDirectLookupMappings(T mapping);


	@Override
	public Mono<Void> handleMessage(Message<?> message) throws MessagingException {
		Match<T> match = getHandlerMethod(message);
		if (match == null) {
			// handleNoMatch would have been invoked already
			return Mono.empty();
		}
		HandlerMethod handlerMethod = match.getHandlerMethod().createWithResolvedBean();
		return this.invocableHelper.handleMessage(handlerMethod, message);
	}

	@Nullable
	private Match<T> getHandlerMethod(Message<?> message) {
		List<Match<T>> matches = new ArrayList<>();

		String destination = getDestination(message);
		List<T> mappingsByUrl = destination != null ? this.destinationLookup.get(destination) : null;
		if (mappingsByUrl != null) {
			addMatchesToCollection(mappingsByUrl, message, matches);
		}
		if (matches.isEmpty()) {
			// No direct hits, go through all mappings
			Set<T> allMappings = this.handlerMethods.keySet();
			addMatchesToCollection(allMappings, message, matches);
		}
		if (matches.isEmpty()) {
			handleNoMatch(destination, message);
			return null;
		}
		Comparator<Match<T>> comparator = new MatchComparator(getMappingComparator(message));
		matches.sort(comparator);
		if (logger.isTraceEnabled()) {
			logger.trace("Found " + matches.size() + " handler methods: " + matches);
		}
		Match<T> bestMatch = matches.get(0);
		if (matches.size() > 1) {
			Match<T> secondBestMatch = matches.get(1);
			if (comparator.compare(bestMatch, secondBestMatch) == 0) {
				HandlerMethod m1 = bestMatch.handlerMethod;
				HandlerMethod m2 = secondBestMatch.handlerMethod;
				throw new IllegalStateException("Ambiguous handler methods mapped for destination '" +
						destination + "': {" + m1.getShortLogMessage() + ", " + m2.getShortLogMessage() + "}");
			}
		}
		return bestMatch;
	}

	/**
	 * Extract a String-based destination, if any, that can be used to perform
	 * a direct look up into the registered mappings.
	 * <p><strong>Note:</strong> This is completely optional. The mapping
	 * metadata for a sub-class may support neither direct lookups, nor String
	 * based destinations.
	 * @see #getDirectLookupMappings(Object)
	 */
	@Nullable
	protected abstract String getDestination(Message<?> message);

	private void addMatchesToCollection(
			Collection<T> mappingsToCheck, Message<?> message, List<Match<T>> matches) {

		for (T mapping : mappingsToCheck) {
			T match = getMatchingMapping(mapping, message);
			if (match != null) {
				matches.add(new Match<T>(match, this.handlerMethods.get(mapping)));
			}
		}
	}

	/**
	 * Check if a mapping matches the current message and return a possibly
	 * new mapping with conditions relevant to the current request.
	 * @param mapping the mapping to get a match for
	 * @param message the message being handled
	 * @return the match or {@code null} if there is no match
	 */
	@Nullable
	protected abstract T getMatchingMapping(T mapping, Message<?> message);

	/**
	 * Return a comparator for sorting matching mappings.
	 * The returned comparator should sort 'better' matches higher.
	 * @param message the current Message
	 * @return the comparator, never {@code null}
	 */
	protected abstract Comparator<T> getMappingComparator(Message<?> message);

	/**
	 * Invoked when no matching handler is found.
	 * @param destination the destination
	 * @param message the message
	 */
	protected void handleNoMatch(@Nullable String destination, Message<?> message) {
		logger.debug("No handlers for destination '" + destination + "'");
	}

	/**
	 * Create a concrete instance of {@link AbstractExceptionHandlerMethodResolver}
	 * that finds exception handling methods based on some criteria, e.g. based
	 * on the presence of {@code @MessageExceptionHandler}.
	 * @param beanType the class in which an exception occurred during handling
	 * @return the resolver to use
	 */
	protected abstract AbstractExceptionHandlerMethodResolver createExceptionMethodResolverFor(Class<?> beanType);


	/**
	 * Container for matched mapping and HandlerMethod. Used for best match
	 * comparison and for access to mapping information.
	 */
	private static class Match<T> {

		private final T mapping;

		private final HandlerMethod handlerMethod;


		Match(T mapping, HandlerMethod handlerMethod) {
			this.mapping = mapping;
			this.handlerMethod = handlerMethod;
		}


		public T getMapping() {
			return this.mapping;
		}

		public HandlerMethod getHandlerMethod() {
			return this.handlerMethod;
		}


		@Override
		public String toString() {
			return this.mapping.toString();
		}
	}


	private class MatchComparator implements Comparator<Match<T>> {

		private final Comparator<T> comparator;


		MatchComparator(Comparator<T> comparator) {
			this.comparator = comparator;
		}


		@Override
		public int compare(Match<T> match1, Match<T> match2) {
			return this.comparator.compare(match1.mapping, match2.mapping);
		}
	}

}
