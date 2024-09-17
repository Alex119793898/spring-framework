/*
 * Copyright 2002-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.beans.factory.support;

import java.beans.ConstructorProperties;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;

import org.springframework.beans.BeanMetadataElement;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.BeansException;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.ConstructorArgumentValues.ValueHolder;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.core.CollectionFactory;
import org.springframework.core.MethodParameter;
import org.springframework.core.NamedThreadLocal;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.MethodInvoker;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * Delegate for resolving constructors and factory methods.
 *
 * <p>Performs constructor resolution through argument matching.
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Mark Fisher
 * @author Costin Leau
 * @author Sebastien Deleuze
 * @author Sam Brannen
 * @since 2.0
 * @see #autowireConstructor
 * @see #instantiateUsingFactoryMethod
 * @see AbstractAutowireCapableBeanFactory
 */
class ConstructorResolver {

	private static final Object[] EMPTY_ARGS = new Object[0];

	private static final NamedThreadLocal<InjectionPoint> currentInjectionPoint =
			new NamedThreadLocal<>("Current injection point");


	private final AbstractAutowireCapableBeanFactory beanFactory;

	private final Log logger;


	/**
	 * Create a new ConstructorResolver for the given factory and instantiation strategy.
	 * @param beanFactory the BeanFactory to work with
	 */
	public ConstructorResolver(AbstractAutowireCapableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
		this.logger = beanFactory.getLogger();
	}


	/**
	 * "autowire constructor" (with constructor arguments by type) behavior.
	 * Also applied if explicit constructor argument values are specified.
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition for the bean
	 * @param chosenCtors chosen candidate constructors (or {@code null} if none)
	 * @param explicitArgs argument values passed in programmatically via the getBean method,
	 * or {@code null} if none (-> use constructor argument values from bean definition)
	 * @return a BeanWrapper for the new instance
	 */
	public BeanWrapper autowireConstructor(String beanName, RootBeanDefinition mbd,
			@Nullable Constructor<?>[] chosenCtors, @Nullable Object[] explicitArgs) {

		// 实例化BeanWrapper。是包装bean的容器
		BeanWrapperImpl bw = new BeanWrapperImpl();
		// 给包装对象设置一些属性
		this.beanFactory.initBeanWrapper(bw);

		// spring对这个bean进行实例化使用的构造函数
		Constructor<?> constructorToUse = null;
		// spring执行构造函数使用的是参数封装类
		ArgumentsHolder argsHolderToUse = null;
		// 参与构造函数实例化过程的参数
		Object[] argsToUse = null;

		// 如果传入参数的话，就直接使用传入的参数
		if (explicitArgs != null) {
			//让argsToUse引用explicitArgs
			argsToUse = explicitArgs;
		}
		// 没有传入参数的话就走else
		else {
			//声明一个要解析的参数值数组，默认为null
			Object[] argsToResolve = null;
			synchronized (mbd.constructorArgumentLock) {
				// 获取BeanDefinition中解析完成的构造函数
				constructorToUse = (Constructor<?>) mbd.resolvedConstructorOrFactoryMethod;
				// BeanDefinition中存在构造函数并且存在构造函数的参数，赋值进行使用
				if (constructorToUse != null && mbd.constructorArgumentsResolved) {
					// Found a cached constructor...
					// 从缓存中找到了构造器，那么继续从缓存中寻找缓存的构造器参数
					argsToUse = mbd.resolvedConstructorArguments;
					if (argsToUse == null) {
						// 没有缓存的参数，就需要获取配置文件中配置的参数
						argsToResolve = mbd.preparedConstructorArguments;
					}
				}
			}
			// 如果缓存中没有缓存的参数的话，即argsToResolve不为空，就需要解析配置的参数
			if (argsToResolve != null) {
				// 解析参数类型，比如将配置的String类型转换为list、boolean等类型
				argsToUse = resolvePreparedArguments(beanName, mbd, bw, constructorToUse, argsToResolve);
			}
		}

		//如果constructorToUse为null或者argsToUser为null
		if (constructorToUse == null || argsToUse == null) {
			// Take specified constructors, if any.
			// 如果传入的构造器数组不为空，就使用传入的过后早期参数，否则通过反射获取class中定义的构造器
			Constructor<?>[] candidates = chosenCtors;

			//如果candidates为null
			if (candidates == null) {
				//获取mbd的Bean类
				Class<?> beanClass = mbd.getBeanClass();
				try {
					// 使用public的构造器或者所有构造器
					candidates = (mbd.isNonPublicAccessAllowed() ?
							beanClass.getDeclaredConstructors() : beanClass.getConstructors());
				}
				//捕捉获取beanClass的构造函数发出的异常
				catch (Throwable ex) {
					throw new BeanCreationException(mbd.getResourceDescription(), beanName,
							"Resolution of declared constructors on bean Class [" + beanClass.getName() +
							"] from ClassLoader [" + beanClass.getClassLoader() + "] failed", ex);
				}
			}

			//如果candidateList只有一个元素 且 没有传入构造函数值 且 mbd也没有构造函数参数值
			//只有一个构造函数不需要选择
			if (candidates.length == 1 && explicitArgs == null && !mbd.hasConstructorArgumentValues()) {
				//获取candidates中唯一的方法
				Constructor<?> uniqueCandidate = candidates[0];
				//如果uniqueCandidate不需要参数
				if (uniqueCandidate.getParameterCount() == 0) {
					//使用mdb的构造函数字段的通用锁【{@link RootBeanDefinition#constructorArgumentLock}】进行加锁以保证线程安全
					synchronized (mbd.constructorArgumentLock) {
						//让mbd缓存已解析的构造函数或工厂方法
						mbd.resolvedConstructorOrFactoryMethod = uniqueCandidate;
						//让mbd标记构造函数参数已解析
						mbd.constructorArgumentsResolved = true;
						//让mbd缓存完全解析的构造函数参数
						mbd.resolvedConstructorArguments = EMPTY_ARGS;
					}
					//使用constructorToUse生成与beanName对应的Bean对象,并将该Bean对象保存到bw中
					bw.setBeanInstance(instantiate(beanName, mbd, uniqueCandidate, EMPTY_ARGS));
					//将bw返回出去
					return bw;
				}
			}

			// Need to resolve the constructor.
			// 自动装配标识，以下有一种情况成立则为true，
			// 1、传进来构造函数，证明spring根据之前代码的判断，知道应该用哪个构造函数，
			// 2、BeanDefinition中设置为构造函数注入模型
			boolean autowiring = (chosenCtors != null ||
					mbd.getResolvedAutowireMode() == AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR);
			//定义一个用于存放解析后的构造函数参数值的ConstructorArgumentValues对象
			ConstructorArgumentValues resolvedValues = null;

			// 构造函数的最小参数个数
			int minNrOfArgs;
			// 如果传入了参与构造函数实例化的参数值，那么参数的数量即为最小参数个数
			if (explicitArgs != null) {
				minNrOfArgs = explicitArgs.length;
			}
			else {
				// 提取配置文件中的配置的构造函数参数
				ConstructorArgumentValues cargs = mbd.getConstructorArgumentValues();
				// 用于承载解析后的构造函数参数的值
				resolvedValues = new ConstructorArgumentValues();
				// 解析到 xml 配置文件中配置的 参数个数
				minNrOfArgs = resolveConstructorArguments(beanName, mbd, bw, cargs, resolvedValues);
			}

			// 对候选的构造函数进行排序，先是访问权限后是参数个数
			// public权限参数数量由多到少
			AutowireUtils.sortConstructors(candidates);
			// 定义一个权重值，变量的大小决定着构造函数是否能够被使用
			int minTypeDiffWeight = Integer.MAX_VALUE;
			// 不明确的构造函数集合，正常情况下差异值不可能相同
			Set<Constructor<?>> ambiguousConstructors = null;
			//定义一个用于UnsatisfiedDependencyException的列表
			Deque<UnsatisfiedDependencyException> causes = null;

			// 循环候选的构造函数
			for (Constructor<?> candidate : candidates) {
				// 获取当前构造函数参数的个数
				int parameterCount = candidate.getParameterCount();

				// 如果已经找到选用的构造函数或者需要的参数个数小于当前的构造函数参数个数则终止，前面已经经过了排序操作
				if (constructorToUse != null && argsToUse != null && argsToUse.length > parameterCount) {
					// Already found greedy constructor that can be satisfied ->
					// do not look any further, there are only less greedy constructors left.
					break;
				}
				//如果本构造函数的参数列表数量小于要求的最小数量，则遍历下一个
				if (parameterCount < minNrOfArgs) {
					continue;
				}

				// 存放构造函数解析完成的参数列表
				ArgumentsHolder argsHolder;
				// 获取参数列表的类型
				Class<?>[] paramTypes = candidate.getParameterTypes();
				// 存在需要解析的构造函数参数
				if (resolvedValues != null) {
					try {
						// 获取构造函数上的ConstructorProperties注解中的参数
						String[] paramNames = ConstructorPropertiesChecker.evaluate(candidate, parameterCount);
						// 如果没有上面的注解，则获取构造函数参数列表中属性的名称
						if (paramNames == null) {
							// 获取参数名称探索器
							ParameterNameDiscoverer pnd = this.beanFactory.getParameterNameDiscoverer();
							if (pnd != null) {
								// 获取指定构造函数的参数名称
								paramNames = pnd.getParameterNames(candidate);
							}
						}
						// 根据名称和数据类型创建参数持有者
						argsHolder = createArgumentArray(beanName, mbd, resolvedValues, bw, paramTypes, paramNames,
								getUserDeclaredConstructor(candidate), autowiring, candidates.length == 1);
					}
					catch (UnsatisfiedDependencyException ex) {
						if (logger.isTraceEnabled()) {
							logger.trace("Ignoring constructor [" + candidate + "] of bean '" + beanName + "': " + ex);
						}
						// Swallow and try next constructor.
						// 吞下并尝试下一个重载的构造函数
						// 如果cause为null
						if (causes == null) {
							//对cause进行实例化成LinkedList对象
							causes = new ArrayDeque<>(1);
						}
						//将ex添加到causes中
						causes.add(ex);
						continue;
					}
				}
				// 不存在构造函数参数列表需要解析的参数
				else {
					// Explicit arguments given -> arguments length must match exactly.
					// 如果参数列表的数量与传入进来的参数数量不相等，继续遍历，否则构造参数列表封装对象
					if (parameterCount != explicitArgs.length) {
						continue;
					}
					// 构造函数没有参数的情况
					argsHolder = new ArgumentsHolder(explicitArgs);
				}

				// 计算权重值，根据要参与构造函数的参数列表和本构造函数的参数列表进行计算
				int typeDiffWeight = (mbd.isLenientConstructorResolution() ?
						argsHolder.getTypeDifferenceWeight(paramTypes) : argsHolder.getAssignabilityWeight(paramTypes));
				// Choose this constructor if it represents the closest match.
				// 本次的构造函数差异值小于上一个构造函数，则进行构造函数更换
				if (typeDiffWeight < minTypeDiffWeight) {
					// 将确定使用的构造函数设置为本构造
					constructorToUse = candidate;
					// 更换使用的构造函数参数封装类
					argsHolderToUse = argsHolder;
					// 更换参与构造函数实例化的参数
					argsToUse = argsHolder.arguments;
					// 差异值更换
					minTypeDiffWeight = typeDiffWeight;
					// 不明确的构造函数列表清空为null
					ambiguousConstructors = null;
				}
				// 差异值相等，则表明构造函数不正常，放入异常集合
				else if (constructorToUse != null && typeDiffWeight == minTypeDiffWeight) {
					//如果ambiguousFactoryMethods为null
					if (ambiguousConstructors == null) {
						//初始化ambiguousFactoryMethods为LinkedHashSet实例
						ambiguousConstructors = new LinkedHashSet<>();
						//将constructorToUse添加到ambiguousFactoryMethods中
						ambiguousConstructors.add(constructorToUse);
					}
					//将candidate添加到ambiguousFactoryMethods中
					ambiguousConstructors.add(candidate);
				}
			}

			//以下两种情况会抛异常
			// 1、没有确定使用的构造函数
			// 2、存在模糊的构造函数并且不允许存在模糊的构造函数
			if (constructorToUse == null) {
				if (causes != null) {
					UnsatisfiedDependencyException ex = causes.removeLast();
					for (Exception cause : causes) {
						this.beanFactory.onSuppressedException(cause);
					}
					throw ex;
				}
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Could not resolve matching constructor on bean class [" + mbd.getBeanClassName() + "] " +
						"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities)");
			}
			else if (ambiguousConstructors != null && !mbd.isLenientConstructorResolution()) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Ambiguous constructor matches found on bean class [" + mbd.getBeanClassName() + "] " +
						"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities): " +
						ambiguousConstructors);
			}

			/**
			 * 没有传入参与构造函数参数列表的参数时，对构造函数缓存到BeanDefinition中
			 * 	1、缓存BeanDefinition进行实例化时使用的构造函数
			 * 	2、缓存BeanDefinition代表的Bean的构造函数已解析完标识
			 * 	3、缓存参与构造函数参数列表值的参数列表
			 */
			if (explicitArgs == null && argsHolderToUse != null) {
				// 将解析的构造函数加入缓存
				argsHolderToUse.storeCache(mbd, constructorToUse);
			}
		}

		Assert.state(argsToUse != null, "Unresolved constructor arguments");
		// 将构造的实例加入BeanWrapper中
		bw.setBeanInstance(instantiate(beanName, mbd, constructorToUse, argsToUse));
		return bw;
	}

	private Object instantiate(
			String beanName, RootBeanDefinition mbd, Constructor<?> constructorToUse, Object[] argsToUse) {

		try {
			InstantiationStrategy strategy = this.beanFactory.getInstantiationStrategy();
			if (System.getSecurityManager() != null) {
				return AccessController.doPrivileged((PrivilegedAction<Object>) () ->
						strategy.instantiate(mbd, beanName, this.beanFactory, constructorToUse, argsToUse),
						this.beanFactory.getAccessControlContext());
			}
			else {
				return strategy.instantiate(mbd, beanName, this.beanFactory, constructorToUse, argsToUse);
			}
		}
		catch (Throwable ex) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName,
					"Bean instantiation via constructor failed", ex);
		}
	}

	/**
	 * Resolve the factory method in the specified bean definition, if possible.
	 * {@link RootBeanDefinition#getResolvedFactoryMethod()} can be checked for the result.
	 * @param mbd the bean definition to check
	 */
	public void resolveFactoryMethodIfPossible(RootBeanDefinition mbd) {
		Class<?> factoryClass;
		boolean isStatic;
		if (mbd.getFactoryBeanName() != null) {
			factoryClass = this.beanFactory.getType(mbd.getFactoryBeanName());
			isStatic = false;
		}
		else {
			factoryClass = mbd.getBeanClass();
			isStatic = true;
		}
		Assert.state(factoryClass != null, "Unresolvable factory class");
		factoryClass = ClassUtils.getUserClass(factoryClass);

		Method[] candidates = getCandidateMethods(factoryClass, mbd);
		Method uniqueCandidate = null;
		for (Method candidate : candidates) {
			if (Modifier.isStatic(candidate.getModifiers()) == isStatic && mbd.isFactoryMethod(candidate)) {
				if (uniqueCandidate == null) {
					uniqueCandidate = candidate;
				}
				else if (isParamMismatch(uniqueCandidate, candidate)) {
					uniqueCandidate = null;
					break;
				}
			}
		}
		mbd.factoryMethodToIntrospect = uniqueCandidate;
	}

	private boolean isParamMismatch(Method uniqueCandidate, Method candidate) {
		int uniqueCandidateParameterCount = uniqueCandidate.getParameterCount();
		int candidateParameterCount = candidate.getParameterCount();
		return (uniqueCandidateParameterCount != candidateParameterCount ||
				!Arrays.equals(uniqueCandidate.getParameterTypes(), candidate.getParameterTypes()));
	}

	/**
	 * Retrieve all candidate methods for the given class, considering
	 * the {@link RootBeanDefinition#isNonPublicAccessAllowed()} flag.
	 * Called as the starting point for factory method determination.
	 */
	private Method[] getCandidateMethods(Class<?> factoryClass, RootBeanDefinition mbd) {
		if (System.getSecurityManager() != null) {
			return AccessController.doPrivileged((PrivilegedAction<Method[]>) () ->
					(mbd.isNonPublicAccessAllowed() ?
						ReflectionUtils.getAllDeclaredMethods(factoryClass) : factoryClass.getMethods()));
		}
		else {
			return (mbd.isNonPublicAccessAllowed() ?
					ReflectionUtils.getAllDeclaredMethods(factoryClass) : factoryClass.getMethods());
		}
	}

	/**
	 * Instantiate the bean using a named factory method. The method may be static, if the
	 * bean definition parameter specifies a class, rather than a "factory-bean", or
	 * an instance variable on a factory object itself configured using Dependency Injection.
	 * <p>Implementation requires iterating over the static or instance methods with the
	 * name specified in the RootBeanDefinition (the method may be overloaded) and trying
	 * to match with the parameters. We don't have the types attached to constructor args,
	 * so trial and error is the only way to go here. The explicitArgs array may contain
	 * argument values passed in programmatically via the corresponding getBean method.
	 * @param beanName the name of the bean
	 * @param mbd the merged bean definition for the bean
	 * @param explicitArgs argument values passed in programmatically via the getBean
	 * method, or {@code null} if none (-> use constructor argument values from bean definition)
	 * @return a BeanWrapper for the new instance
	 */
	public BeanWrapper instantiateUsingFactoryMethod(
			String beanName, RootBeanDefinition mbd, @Nullable Object[] explicitArgs) {
		// 新建一个BeanWrapperImp实例，用于封装使用工厂方法生成与beanName对应的Bean对象
		BeanWrapperImpl bw = new BeanWrapperImpl();
		// 初始化实例包装类
		this.beanFactory.initBeanWrapper(bw);

		// 获取 FactoryBean 对象，FactoryBean 对象的类对象，确定工厂方法是否是静态
		// 定义一个用于存放工厂Bean对象的Object
		Object factoryBean;
		// 定义一个用于存放 FactoryBean 对象的类对象的Class
		Class<?> factoryClass;
		// 定义一个表示是静态工厂方法的标记
		boolean isStatic;

		// 从mbd中获取配置的 FactoryBean 名
		String factoryBeanName = mbd.getFactoryBeanName();
		// 如果factoryBeanName不为null
		if (factoryBeanName != null) {
			// 如果factoryBean名与beanName相同
			if (factoryBeanName.equals(beanName)) {
				throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
						"factory-bean reference points back to the same bean definition");
			}
			// 从bean工厂中获取factoryBeanName所指的factoryBean对象
			factoryBean = this.beanFactory.getBean(factoryBeanName);
			if (mbd.isSingleton() && this.beanFactory.containsSingleton(beanName)) {
				// 这个时候意味着Bean工厂中已经有beanName的Bean对象，而这个还要生成多一个Bean名为BeanName的Bean对象，导致了冲突
				// 抛出ImplicitlyAppearedSingletonException，
				throw new ImplicitlyAppearedSingletonException();
			}
			this.beanFactory.registerDependentBean(factoryBeanName, beanName);

			// 获取factoryBean的Class对象
			factoryClass = factoryBean.getClass();
			// 设置isStatic为false,表示不是静态方法
			isStatic = false;
		}
		else {
			// It's a static factory method on the bean class.
			// 这是bean类上的静态工厂方法
			// 如果mbd没有指定bean类
			if (!mbd.hasBeanClass()) {
				throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
						"bean definition declares neither a bean class nor a factory-bean reference");
			}
			// 将factoryBean设为null
			factoryBean = null;
			// 指定factoryClass引用mbd指定的bean类
			factoryClass = mbd.getBeanClass();
			// 设置isStatic为true，表示是静态方法
			isStatic = true;
		}

		// 尝试从mbd的缓存属性中获取要使用的工厂方法: 使用的参数值数组
		// 声明一个要使用的工厂方法，默认为null
		Method factoryMethodToUse = null;
		// 声明一个用于存储不同形式的参数值的ArgumentsHolder，默认为null
		ArgumentsHolder argsHolderToUse = null;
		// 声明一个要使用的参数值数组,默认为null
		Object[] argsToUse = null;

		// 如果explicitArgs不为null【explicitArgs是传入的参数，一般都是null】
		if (explicitArgs != null) {
			// argsToUse就引用explicitArgs
			argsToUse = explicitArgs;
		}
		else {
			// 如果没有传
			// 声明一个要解析的参数值数组，默认为null
			Object[] argsToResolve = null;
			// 使用mbd的构造函数字段通用锁进行加锁，以保证线程安全
			synchronized (mbd.constructorArgumentLock) {
				// 指定factoryMethodToUser引用mbd已解析的构造函数或工厂方法对象
				factoryMethodToUse = (Method) mbd.resolvedConstructorOrFactoryMethod;
				// 如果factoryMethodToUser不为null且mbd已解析构造函数参数
				if (factoryMethodToUse != null && mbd.constructorArgumentsResolved) {
					// Found a cached factory method...
					// 找到了缓存的工厂方法
					// 指定argsToUser引用mbd完全解析的构造函数参数值
					argsToUse = mbd.resolvedConstructorArguments;
					// 如果argsToUse为null
					if (argsToUse == null) {
						// 指定argsToResolve引用mbd部分准备好的构造函数参数值
						argsToResolve = mbd.preparedConstructorArguments;
					}
				}
			}
			// 如果argsToResolve不为null,即表示mbd还没有完全解析的构造函数参数值
			if (argsToResolve != null) {
				// 解析缓存在mbd中准备好的参数值,允许在没有此类BeanDefintion的时候回退
				argsToUse = resolvePreparedArguments(beanName, mbd, bw, factoryMethodToUse, argsToResolve);
			}
		}
		// 如果没解析过，就获取factoryClass的用户定义类型，因为此时factoryClass可能是CGLIB动态代理类型，
		// 所以要获取用父类的类型。如果工厂方法是唯一的，就是没重载的，就获取解析的工厂方法，如果不为空，就添加到一个不可变列表里，
		// 如果为空的话，就要去找出factoryClass的以及父类的所有的方法，进一步找出方法修饰符一致且名字跟工厂方法名字相同的且是bean注解的方法，并放入列表里。
		if (factoryMethodToUse == null || argsToUse == null) {
			// Need to determine the factory method...
			// Try all methods with this name to see if they match the given arguments.
			// 获取工厂类的所有候选工厂方法
			factoryClass = ClassUtils.getUserClass(factoryClass);

			// 定义一个用于存储候选方法的集合
			List<Method> candidates = null;
			// 如果mbd所配置工厂方法时唯一
			if (mbd.isFactoryMethodUnique) {
				// 如果factoryMethodToUse为null
				if (factoryMethodToUse == null) {
					// 获取mbd解析后的工厂方法对象
					factoryMethodToUse = mbd.getResolvedFactoryMethod();
				}
				// 如果factoryMethodToUse不为null
				if (factoryMethodToUse != null) {
					// Collections.singletonList()返回的是不可变的集合，但是这个长度的集合只有1，可以减少内存空间。但是返回的值依然是Collections的内部实现类，
					// 同样没有add的方法，调用add，set方法会报错
					// 新建一个不可变，只能存一个对象的集合，将factoryMethodToUse添加进行，然后让candidateList引用该集合
					candidates = Collections.singletonList(factoryMethodToUse);
				}
			}
			// 如果candidateList为null
			if (candidates == null) {
				// 让candidateList引用一个新的ArrayList
				candidates = new ArrayList<>();
				// 根据mbd的是否允许访问非公共构造函数和方法标记【RootBeanDefinition.isNonPublicAccessAllowed】来获取factoryClass的所有候选方法
				// 这里会获取到 Object 上的12个方法 + 存在的同名的 factoryMethod
				Method[] rawCandidates = getCandidateMethods(factoryClass, mbd);
				// 遍历rawCandidates,元素名为candidate
				for (Method candidate : rawCandidates) {
					// 如果candidate的修饰符与isStatic一致且candidate有资格作为mdb的工厂方法
					if (Modifier.isStatic(candidate.getModifiers()) == isStatic && mbd.isFactoryMethod(candidate)) {
						// 将candidate添加到candidateList中
						candidates.add(candidate);
					}
				}
			}
			// 候选方法只有一个且没有构造函数时，就直接使用该候选方法生成与beanName对应的Bean对象封装到bw中返回出去
			// 如果candidateList只有一个元素且没有传入构造函数值且mbd也没有构造函数参数值
			if (candidates.size() == 1 && explicitArgs == null && !mbd.hasConstructorArgumentValues()) {
				// 获取candidateList中唯一的方法
				Method uniqueCandidate = candidates.get(0);
				// 如果uniqueCandidate是不需要参数
				if (uniqueCandidate.getParameterCount() == 0) {
					// 让mbd缓存uniqueCandidate【{@link RootBeanDefinition#factoryMethodToIntrospect}】
					mbd.factoryMethodToIntrospect = uniqueCandidate;
					// 使用mdb的构造函数字段的通用锁【{@link RootBeanDefinition#constructorArgumentLock}】进行加锁以保证线程安全
					synchronized (mbd.constructorArgumentLock) {
						// 让mbd缓存已解析的构造函数或工厂方法【{@link RootBeanDefinition#resolvedConstructorOrFactoryMethod}】
						mbd.resolvedConstructorOrFactoryMethod = uniqueCandidate;
						// 让mbd标记构造函数参数已解析【{@link RootBeanDefinition#constructorArgumentsResolved}】
						mbd.constructorArgumentsResolved = true;
						// 让mbd缓存完全解析的构造函数参数【{@link RootBeanDefinition#resolvedConstructorArguments}】
						mbd.resolvedConstructorArguments = EMPTY_ARGS;
					}
					// 使用factoryBean生成的与beanName对应的Bean对象,并将该Bean对象保存到bw中
					bw.setBeanInstance(instantiate(beanName, mbd, factoryBean, uniqueCandidate, EMPTY_ARGS));
					// 将bw返回出去
					return bw;
				}
			}
			// 如果有多个工厂方法的话进行排序操作
			if (candidates.size() > 1) {  // explicitly skip immutable singletonList
				candidates.sort(AutowireUtils.EXECUTABLE_COMPARATOR);
			}
			// ConstructorArgumentValues：构造函数参数值的Holder,通常作为BeanDefinition的一部分,支持构造函数参数列表中特定索引的值
			// 以及按类型的通用参数匹配
			// 定义一个用于存放解析后的构造函数参数值的ConstructorArgumentValues对象
			ConstructorArgumentValues resolvedValues = null;
			// 定义一个mbd是否支持使用构造函数进行自动注入的标记
			boolean autowiring = (mbd.getResolvedAutowireMode() == AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR);
			// 定义一个最小类型差异权重，默认是Integer最大值
			int minTypeDiffWeight = Integer.MAX_VALUE;
			// 定义一个存储摸棱两可的工厂方法的Set集合,以用于抛出BeanCreationException时描述异常信息
			Set<Method> ambiguousFactoryMethods = null;

			// 定义一个最少参数数，默认为0
			int minNrOfArgs;
			// 如果explicitArgs不为null
			if (explicitArgs != null) {
				// minNrOfArgs引用explicitArgs的数组长度
				minNrOfArgs = explicitArgs.length;
			}
			else {
				// We don't have arguments passed in programmatically, so we need to resolve the
				// arguments specified in the constructor arguments held in the bean definition.
				// 我们没有以编程方式传递参数，因此我们需要解析BeanDefinition中保存的构造函数参数中指定的参数
				// 如果mbd有构造函数参数值
				if (mbd.hasConstructorArgumentValues()) {
					// 获取mbd的构造函数参数值Holder
					ConstructorArgumentValues cargs = mbd.getConstructorArgumentValues();
					// 对resolvedValues实例化
					resolvedValues = new ConstructorArgumentValues();
					// 将cargs解析后值保存到resolveValues中，并让minNrOfArgs引用解析后的最小(索引参数值数+泛型参数值数)
					minNrOfArgs = resolveConstructorArguments(beanName, mbd, bw, cargs, resolvedValues);
				}
				else {
					// 意味着mbd没有构造函数参数值时，将minNrOfArgs设为0
					minNrOfArgs = 0;
				}
			}

			// 定义一个用于UnsatisfiedDependencyException的列表
			Deque<UnsatisfiedDependencyException> causes = null;

			// 遍历candidates，元素名为candidate
			for (Method candidate : candidates) {
				// 获取参数的个数
				int parameterCount = candidate.getParameterCount();

				// 如果paramTypes的数组长度大于等于minNrOfArgs
				if (parameterCount >= minNrOfArgs) {
					ArgumentsHolder argsHolder;

					// argsHolder 参数的处理
					Class<?>[] paramTypes = candidate.getParameterTypes();
					if (explicitArgs != null) {
						// Explicit arguments given -> arguments length must match exactly.
						// 给定的显示参数->参数长度必须完全匹配
						// 如果paramTypes的长度与explicitArgsd额长度不相等
						if (paramTypes.length != explicitArgs.length) {
							// 跳过当次循环中剩下的步骤，执行下一次循环。
							continue;
						}
						// 实例化argsHolder，封装explicitArgs到argsHolder
						argsHolder = new ArgumentsHolder(explicitArgs);
					}
					else {
						// Resolved constructor arguments: type conversion and/or autowiring necessary.
						// 已解析的构造函数参数:类型转换 and/or 自动注入时必须的
						try {
							// 定义用于保存参数名的数组
							String[] paramNames = null;
							// 获取beanFactory的参数名发现器
							ParameterNameDiscoverer pnd = this.beanFactory.getParameterNameDiscoverer();
							// 如果pnd不为null
							if (pnd != null) {
								// 通过pnd解析candidate的参数名
								paramNames = pnd.getParameterNames(candidate);
							}
							// 将resolvedValues转换成一个封装着参数数组ArgumentsHolder实例，当candidate只有一个时，支持可在抛
							// 出没有此类BeanDefinition的异常返回null，而不抛出异常
							argsHolder = createArgumentArray(beanName, mbd, resolvedValues, bw,
									paramTypes, paramNames, candidate, autowiring, candidates.size() == 1);
						}
						// 捕捉UnsatisfiedDependencyException
						catch (UnsatisfiedDependencyException ex) {
							if (logger.isTraceEnabled()) {
								logger.trace("Ignoring factory method [" + candidate + "] of bean '" + beanName + "': " + ex);
							}
							// Swallow and try next overloaded factory method.
							// 吞下并尝试下一个重载的工厂方法
							// 如果cause为null
							if (causes == null) {
								// 对cause进行实例化成LinkedList对象
								causes = new ArrayDeque<>(1);
							}
							// 将ex添加到causes中
							causes.add(ex);
							// 跳过本次循环体中余下尚未执行的语句，立即进行下一次的循环
							continue;
						}
					}

					// mbd支持的构造函数解析模式,默认使用宽松模式:
					// 1. 严格模式如果摸棱两可的构造函数在转换参数时都匹配，则抛出异常
					// 2. 宽松模式将使用"最接近类型匹配"的构造函数
					// 如果bd支持的构造函数解析模式时宽松模式,引用获取类型差异权重值，否则引用获取 Assignabliity 权重值
					//------------------------------------------------------------------------------------------
					// 【计算 typeDiffWeight 权重值可以 baidu 查询：里边有一套算法】
					// 这里只关注：可以通过 typeDiffWeight 权重值，筛选到我们的 static Person getPerson() 方法即可
					//------------------------------------------------------------------------------------------
					int typeDiffWeight = (mbd.isLenientConstructorResolution() ?
							argsHolder.getTypeDifferenceWeight(paramTypes) : argsHolder.getAssignabilityWeight(paramTypes));
					// Choose this factory method if it represents the closest match.
					// ------------------------------------------------------------------------------------------
					// 如果它表示最接近的匹配项，则选择此工厂方法
					// 如果typeDiffWeight小于minTypeDiffWeight, minTypeDiffWeight = typeDiffWeight;进行赋值
					// 循环中一次次计算typeDiffWeight, 一次次比较,值越小越匹配
					// 最终选择最匹配的 factoryMethodToUse 和 argsToUse
					// ------------------------------------------------------------------------------------------
					if (typeDiffWeight < minTypeDiffWeight) {
						// 让factoryMethodToUser引用candidate
						factoryMethodToUse = candidate;
						// 让argsHolderToUse引用argsHolder
						argsHolderToUse = argsHolder;
						// 让argToUse引用argsHolder的经过转换后参数值数组
						argsToUse = argsHolder.arguments;
						// 让minTypeDiffWeight引用typeDiffWeight
						minTypeDiffWeight = typeDiffWeight;
						// 将ambiguousFactoryMethods置为null
						ambiguousFactoryMethods = null;
					}
					// Find out about ambiguity: In case of the same type difference weight
					// for methods with the same number of parameters, collect such candidates
					// and eventually raise an ambiguity exception.
					// However, only perform that check in non-lenient constructor resolution mode,
					// and explicitly ignore overridden methods (with the same parameter signature).
					// 找出歧义:如果具有相同数量参数的方法具有相同的类型差异权重，则收集此类候选想并最终引发歧义异常。
					// 但是，仅在非宽松构造函数解析模式下执行该检查，并显示忽略的方法（具有相同的参数签名）
					// 如果factoryMethodToUse不为null且typeDiffWeight与minTypeDiffWeight相等
					// 且mbd指定了严格模式解析构造函数且paramTypes的数组长度与factoryMethodToUse的参数数组长度相等且
					// paramTypes的数组元素与factoryMethodToUse的参数数组元素不相等
					else if (factoryMethodToUse != null && typeDiffWeight == minTypeDiffWeight &&
							!mbd.isLenientConstructorResolution() &&
							paramTypes.length == factoryMethodToUse.getParameterCount() &&
							!Arrays.equals(paramTypes, factoryMethodToUse.getParameterTypes())) {
						// 如果ambiguousFactoryMethods为null
						if (ambiguousFactoryMethods == null) {
							// 初始化ambiguousFactoryMethods为LinkedHashSet实例
							ambiguousFactoryMethods = new LinkedHashSet<>();
							// 将factoryMethodToUse添加到ambiguousFactoryMethods中
							ambiguousFactoryMethods.add(factoryMethodToUse);
						}
						// 将candidate添加到ambiguousFactoryMethods中
						ambiguousFactoryMethods.add(candidate);
					}
				}
			}

			// ------------------------------------------------------------------------------------------
			// 后边都是依然没有选择到 ,处理详细的异常信息细节：有哪些匹配不合适的
			// ------------------------------------------------------------------------------------------

			// 整合无法筛选出候选方法或者无法解析出要使用的参数值的情况，抛出BeanCreationException并加以描述
			// 如果factoryMethodToUse为null或者argsToUse为null
			if (factoryMethodToUse == null || argsToUse == null) {
				// 如果causes不为null
				if (causes != null) {
					// 从cause中移除最新的UnsatisfiedDependencyException
					UnsatisfiedDependencyException ex = causes.removeLast();
					// 遍历causes,元素为cause
					for (Exception cause : causes) {
						// 将cause添加到该Bean工厂的抑制异常列表【{@link DefaultSingletonBeanRegistry#suppressedExceptions】中
						this.beanFactory.onSuppressedException(cause);
					}
					// 重新抛出ex
					throw ex;
				}
				// 定义一个用于存放参数类型的简单类名的ArrayList对象，长度为minNrOfArgs
				List<String> argTypes = new ArrayList<>(minNrOfArgs);
				// 如果explicitArgs不为null
				if (explicitArgs != null) {
					// 遍历explicitArgs.元素为arg
					for (Object arg : explicitArgs) {
						// 如果arg不为null，将arg的简单类名添加到argTypes中；否则将"null"添加到argTypes中
						argTypes.add(arg != null ? arg.getClass().getSimpleName() : "null");
					}
				}
				// 如果resolvedValues不为null
				else if (resolvedValues != null) {
					// 定义一个用于存放resolvedValues的泛型参数值和方法参数值的LinkedHashSet对象
					Set<ValueHolder> valueHolders = new LinkedHashSet<>(resolvedValues.getArgumentCount());
					// 将resolvedValues的方法参数值添加到valueHolders中
					valueHolders.addAll(resolvedValues.getIndexedArgumentValues().values());
					// 将resolvedValues的泛型参数值添加到valueHolders中
					valueHolders.addAll(resolvedValues.getGenericArgumentValues());
					// 遍历valueHolders，元素为value
					for (ValueHolder value : valueHolders) {
						// 如果value的参数类型不为null，就获取该参数类型的简单类名；否则(如果value的参数值不为null，即获取该参数值的简单类名;否则为"null")
						String argType = (value.getType() != null ? ClassUtils.getShortName(value.getType()) :
								(value.getValue() != null ? value.getValue().getClass().getSimpleName() : "null"));
						// 将argType添加到argTypes中
						argTypes.add(argType);
					}
				}
				// 将argType转换成字符串，以","隔开元素.用于描述Bean创建异常
				String argDesc = StringUtils.collectionToCommaDelimitedString(argTypes);
				// 抛出BeanCreationException:找不到匹配的工厂方法：工厂Bean'mbd.getFactoryBeanName()';工厂方法
				// 'mbd.getFactoryMethodName()(argDesc)'.检查是否存在具体指定名称和参数的方法，并且该方法时静态/非静态的.
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"No matching factory method found on class [" + factoryClass.getName() + "]: " +
						(mbd.getFactoryBeanName() != null ? "factory bean '" + mbd.getFactoryBeanName() + "'; " : "") +
						"factory method '" + mbd.getFactoryMethodName() + "(" + argDesc + ")'. " +
						"Check that a method with the specified name " + (minNrOfArgs > 0 ? "and arguments " : "") +
						"exists and that it is " + (isStatic ? "static" : "non-static") + ".");
			}
			// 如果factoryMethodToUse时无返回值方法
			else if (void.class == factoryMethodToUse.getReturnType()) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Invalid factory method '" + mbd.getFactoryMethodName() + "' on class [" +
						factoryClass.getName() + "]: needs to have a non-void return type!");
			}
			// 如果ambiguousFactoryMethods不为null
			else if (ambiguousFactoryMethods != null) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Ambiguous factory method matches found on class [" + factoryClass.getName() + "] " +
						"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities): " +
						ambiguousFactoryMethods);
			}

			//------------------------------------------------------------------------------------------
			// 如果explicitArgs为null且argsHolderToUser不为null
			// 解释：如果没有通过getBean方法传入参数,将筛选出来的工厂方法和解析出来的参数值缓存到mdb中
			//------------------------------------------------------------------------------------------
			if (explicitArgs == null && argsHolderToUse != null) {
				// 让mbd的唯一方法候选【{@link RootBeanDefinition#factoryMethodToIntrospect}】引用factoryMethodToUse
				mbd.factoryMethodToIntrospect = factoryMethodToUse;
				// 将argsHolderToUse所得到的参数值属性缓存到mbd对应的属性中
				argsHolderToUse.storeCache(mbd, factoryMethodToUse);
			}
		}

		//------------------------------------------------------------------------------------------
		// 用已经找的的 factoryMethodToUse + argsToUse 进行实例化操作
		// 并且
		// 使用factoryBean生成与beanName对应的Bean对象,并将该Bean对象保存到bw中
		//------------------------------------------------------------------------------------------

		bw.setBeanInstance(instantiate(beanName, mbd, factoryBean, factoryMethodToUse, argsToUse));
		// 将bw返回出去
		return bw;
	}

	private Object instantiate(String beanName, RootBeanDefinition mbd,
			@Nullable Object factoryBean, Method factoryMethod, Object[] args) {

		try {
			if (System.getSecurityManager() != null) {
				return AccessController.doPrivileged((PrivilegedAction<Object>) () ->
						this.beanFactory.getInstantiationStrategy().instantiate(
								mbd, beanName, this.beanFactory, factoryBean, factoryMethod, args),
						this.beanFactory.getAccessControlContext());
			}
			else {
				return this.beanFactory.getInstantiationStrategy().instantiate(
						mbd, beanName, this.beanFactory, factoryBean, factoryMethod, args);
			}
		}
		catch (Throwable ex) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName,
					"Bean instantiation via factory method failed", ex);
		}
	}

	/**
	 * Resolve the constructor arguments for this bean into the resolvedValues object.
	 * This may involve looking up other beans.
	 * <p>This method is also used for handling invocations of static factory methods.
	 */
	private int resolveConstructorArguments(String beanName, RootBeanDefinition mbd, BeanWrapper bw,
			ConstructorArgumentValues cargs, ConstructorArgumentValues resolvedValues) {

		TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
		TypeConverter converter = (customConverter != null ? customConverter : bw);
		BeanDefinitionValueResolver valueResolver =
				new BeanDefinitionValueResolver(this.beanFactory, beanName, mbd, converter);

		int minNrOfArgs = cargs.getArgumentCount();

		for (Map.Entry<Integer, ConstructorArgumentValues.ValueHolder> entry : cargs.getIndexedArgumentValues().entrySet()) {
			int index = entry.getKey();
			if (index < 0) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Invalid constructor argument index: " + index);
			}
			if (index + 1 > minNrOfArgs) {
				minNrOfArgs = index + 1;
			}
			ConstructorArgumentValues.ValueHolder valueHolder = entry.getValue();
			if (valueHolder.isConverted()) {
				resolvedValues.addIndexedArgumentValue(index, valueHolder);
			}
			else {
				Object resolvedValue =
						valueResolver.resolveValueIfNecessary("constructor argument", valueHolder.getValue());
				ConstructorArgumentValues.ValueHolder resolvedValueHolder =
						new ConstructorArgumentValues.ValueHolder(resolvedValue, valueHolder.getType(), valueHolder.getName());
				resolvedValueHolder.setSource(valueHolder);
				resolvedValues.addIndexedArgumentValue(index, resolvedValueHolder);
			}
		}

		for (ConstructorArgumentValues.ValueHolder valueHolder : cargs.getGenericArgumentValues()) {
			if (valueHolder.isConverted()) {
				resolvedValues.addGenericArgumentValue(valueHolder);
			}
			else {
				Object resolvedValue =
						valueResolver.resolveValueIfNecessary("constructor argument", valueHolder.getValue());
				ConstructorArgumentValues.ValueHolder resolvedValueHolder = new ConstructorArgumentValues.ValueHolder(
						resolvedValue, valueHolder.getType(), valueHolder.getName());
				resolvedValueHolder.setSource(valueHolder);
				resolvedValues.addGenericArgumentValue(resolvedValueHolder);
			}
		}

		return minNrOfArgs;
	}

	/**
	 * Create an array of arguments to invoke a constructor or factory method,
	 * given the resolved constructor argument values.
	 */
	private ArgumentsHolder createArgumentArray(
			String beanName, RootBeanDefinition mbd, @Nullable ConstructorArgumentValues resolvedValues,
			BeanWrapper bw, Class<?>[] paramTypes, @Nullable String[] paramNames, Executable executable,
			boolean autowiring, boolean fallback) throws UnsatisfiedDependencyException {

		TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
		TypeConverter converter = (customConverter != null ? customConverter : bw);

		ArgumentsHolder args = new ArgumentsHolder(paramTypes.length);
		Set<ConstructorArgumentValues.ValueHolder> usedValueHolders = new HashSet<>(paramTypes.length);
		Set<String> allAutowiredBeanNames = new LinkedHashSet<>(paramTypes.length * 2);

		for (int paramIndex = 0; paramIndex < paramTypes.length; paramIndex++) {
			Class<?> paramType = paramTypes[paramIndex];
			String paramName = (paramNames != null ? paramNames[paramIndex] : "");
			// Try to find matching constructor argument value, either indexed or generic.
			ConstructorArgumentValues.ValueHolder valueHolder = null;
			if (resolvedValues != null) {
				valueHolder = resolvedValues.getArgumentValue(paramIndex, paramType, paramName, usedValueHolders);
				// If we couldn't find a direct match and are not supposed to autowire,
				// let's try the next generic, untyped argument value as fallback:
				// it could match after type conversion (for example, String -> int).
				if (valueHolder == null && (!autowiring || paramTypes.length == resolvedValues.getArgumentCount())) {
					valueHolder = resolvedValues.getGenericArgumentValue(null, null, usedValueHolders);
				}
			}
			if (valueHolder != null) {
				// We found a potential match - let's give it a try.
				// Do not consider the same value definition multiple times!
				usedValueHolders.add(valueHolder);
				Object originalValue = valueHolder.getValue();
				Object convertedValue;
				if (valueHolder.isConverted()) {
					convertedValue = valueHolder.getConvertedValue();
					args.preparedArguments[paramIndex] = convertedValue;
				}
				else {
					MethodParameter methodParam = MethodParameter.forExecutable(executable, paramIndex);
					try {
						convertedValue = converter.convertIfNecessary(originalValue, paramType, methodParam);
					}
					catch (TypeMismatchException ex) {
						throw new UnsatisfiedDependencyException(
								mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam),
								"Could not convert argument value of type [" +
								ObjectUtils.nullSafeClassName(valueHolder.getValue()) +
								"] to required type [" + paramType.getName() + "]: " + ex.getMessage());
					}
					Object sourceHolder = valueHolder.getSource();
					if (sourceHolder instanceof ConstructorArgumentValues.ValueHolder) {
						Object sourceValue = ((ConstructorArgumentValues.ValueHolder) sourceHolder).getValue();
						args.resolveNecessary = true;
						args.preparedArguments[paramIndex] = sourceValue;
					}
				}
				args.arguments[paramIndex] = convertedValue;
				args.rawArguments[paramIndex] = originalValue;
			}
			else {
				MethodParameter methodParam = MethodParameter.forExecutable(executable, paramIndex);
				// No explicit match found: we're either supposed to autowire or
				// have to fail creating an argument array for the given constructor.
				if (!autowiring) {
					throw new UnsatisfiedDependencyException(
							mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam),
							"Ambiguous argument values for parameter of type [" + paramType.getName() +
							"] - did you specify the correct bean references as arguments?");
				}
				try {
					ConstructorDependencyDescriptor desc = new ConstructorDependencyDescriptor(methodParam, true);
					Set<String> autowiredBeanNames = new LinkedHashSet<>(2);
					Object arg = resolveAutowiredArgument(
							desc, paramType, beanName, autowiredBeanNames, converter, fallback);
					if (arg != null) {
						setShortcutIfPossible(desc, paramType, autowiredBeanNames);
					}
					allAutowiredBeanNames.addAll(autowiredBeanNames);
					args.rawArguments[paramIndex] = arg;
					args.arguments[paramIndex] = arg;
					args.preparedArguments[paramIndex] = desc;
					args.resolveNecessary = true;
				}
				catch (BeansException ex) {
					throw new UnsatisfiedDependencyException(
							mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam), ex);
				}
			}
		}

		registerDependentBeans(executable, beanName, allAutowiredBeanNames);

		return args;
	}

	/**
	 * Resolve the prepared arguments stored in the given bean definition.
	 */
	private Object[] resolvePreparedArguments(String beanName, RootBeanDefinition mbd, BeanWrapper bw,
			Executable executable, Object[] argsToResolve) {

		TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
		TypeConverter converter = (customConverter != null ? customConverter : bw);
		BeanDefinitionValueResolver valueResolver =
				new BeanDefinitionValueResolver(this.beanFactory, beanName, mbd, converter);
		Class<?>[] paramTypes = executable.getParameterTypes();

		Object[] resolvedArgs = new Object[argsToResolve.length];
		for (int argIndex = 0; argIndex < argsToResolve.length; argIndex++) {
			Object argValue = argsToResolve[argIndex];
			Class<?> paramType = paramTypes[argIndex];
			boolean convertNecessary = false;
			if (argValue instanceof ConstructorDependencyDescriptor) {
				ConstructorDependencyDescriptor descriptor = (ConstructorDependencyDescriptor) argValue;
				try {
					argValue = resolveAutowiredArgument(descriptor, paramType, beanName,
							null, converter, true);
				}
				catch (BeansException ex) {
					// Unexpected target bean mismatch for cached argument -> re-resolve
					Set<String> autowiredBeanNames = null;
					if (descriptor.hasShortcut()) {
						// Reset shortcut and try to re-resolve it in this thread...
						descriptor.setShortcut(null);
						autowiredBeanNames = new LinkedHashSet<>(2);
					}
					logger.debug("Failed to resolve cached argument", ex);
					argValue = resolveAutowiredArgument(descriptor, paramType, beanName,
							autowiredBeanNames, converter, true);
					if (autowiredBeanNames != null && !descriptor.hasShortcut()) {
						// We encountered as stale shortcut before, and the shortcut has
						// not been re-resolved by another thread in the meantime...
						if (argValue != null) {
							setShortcutIfPossible(descriptor, paramType, autowiredBeanNames);
						}
						registerDependentBeans(executable, beanName, autowiredBeanNames);
					}
				}
			}
			else if (argValue instanceof BeanMetadataElement) {
				argValue = valueResolver.resolveValueIfNecessary("constructor argument", argValue);
				convertNecessary = true;
			}
			else if (argValue instanceof String) {
				argValue = this.beanFactory.evaluateBeanDefinitionString((String) argValue, mbd);
				convertNecessary = true;
			}
			if (convertNecessary) {
				MethodParameter methodParam = MethodParameter.forExecutable(executable, argIndex);
				try {
					argValue = converter.convertIfNecessary(argValue, paramType, methodParam);
				}
				catch (TypeMismatchException ex) {
					throw new UnsatisfiedDependencyException(
							mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam),
							"Could not convert argument value of type [" + ObjectUtils.nullSafeClassName(argValue) +
							"] to required type [" + paramType.getName() + "]: " + ex.getMessage());
				}
			}
			resolvedArgs[argIndex] = argValue;
		}
		return resolvedArgs;
	}

	private Constructor<?> getUserDeclaredConstructor(Constructor<?> constructor) {
		Class<?> declaringClass = constructor.getDeclaringClass();
		Class<?> userClass = ClassUtils.getUserClass(declaringClass);
		if (userClass != declaringClass) {
			try {
				return userClass.getDeclaredConstructor(constructor.getParameterTypes());
			}
			catch (NoSuchMethodException ex) {
				// No equivalent constructor on user class (superclass)...
				// Let's proceed with the given constructor as we usually would.
			}
		}
		return constructor;
	}

	/**
	 * Resolve the specified argument which is supposed to be autowired.
	 */
	@Nullable
	Object resolveAutowiredArgument(DependencyDescriptor descriptor, Class<?> paramType, String beanName,
			@Nullable Set<String> autowiredBeanNames, TypeConverter typeConverter, boolean fallback) {

		if (InjectionPoint.class.isAssignableFrom(paramType)) {
			InjectionPoint injectionPoint = currentInjectionPoint.get();
			if (injectionPoint == null) {
				throw new IllegalStateException("No current InjectionPoint available for " + descriptor);
			}
			return injectionPoint;
		}

		try {
			return this.beanFactory.resolveDependency(descriptor, beanName, autowiredBeanNames, typeConverter);
		}
		catch (NoUniqueBeanDefinitionException ex) {
			throw ex;
		}
		catch (NoSuchBeanDefinitionException ex) {
			if (fallback) {
				// Single constructor or factory method -> let's return an empty array/collection
				// for e.g. a vararg or a non-null List/Set/Map parameter.
				if (paramType.isArray()) {
					return Array.newInstance(paramType.getComponentType(), 0);
				}
				else if (CollectionFactory.isApproximableCollectionType(paramType)) {
					return CollectionFactory.createCollection(paramType, 0);
				}
				else if (CollectionFactory.isApproximableMapType(paramType)) {
					return CollectionFactory.createMap(paramType, 0);
				}
			}
			throw ex;
		}
	}

	private void setShortcutIfPossible(
			ConstructorDependencyDescriptor descriptor, Class<?> paramType, Set<String> autowiredBeanNames) {

		if (autowiredBeanNames.size() == 1) {
			String autowiredBeanName = autowiredBeanNames.iterator().next();
			if (this.beanFactory.containsBean(autowiredBeanName) &&
					this.beanFactory.isTypeMatch(autowiredBeanName, paramType)) {
				descriptor.setShortcut(autowiredBeanName);
			}
		}
	}

	private void registerDependentBeans(
			Executable executable, String beanName, Set<String> autowiredBeanNames) {

		for (String autowiredBeanName : autowiredBeanNames) {
			this.beanFactory.registerDependentBean(autowiredBeanName, beanName);
			if (logger.isDebugEnabled()) {
				logger.debug("Autowiring by type from bean name '" + beanName + "' via " +
						(executable instanceof Constructor ? "constructor" : "factory method") +
						" to bean named '" + autowiredBeanName + "'");
			}
		}
	}

	static InjectionPoint setCurrentInjectionPoint(@Nullable InjectionPoint injectionPoint) {
		InjectionPoint old = currentInjectionPoint.get();
		if (injectionPoint != null) {
			currentInjectionPoint.set(injectionPoint);
		}
		else {
			currentInjectionPoint.remove();
		}
		return old;
	}


	/**
	 * Private inner class for holding argument combinations.
	 */
	private static class ArgumentsHolder {

		public final Object[] rawArguments;

		public final Object[] arguments;

		public final Object[] preparedArguments;

		public boolean resolveNecessary = false;

		public ArgumentsHolder(int size) {
			this.rawArguments = new Object[size];
			this.arguments = new Object[size];
			this.preparedArguments = new Object[size];
		}

		public ArgumentsHolder(Object[] args) {
			this.rawArguments = args;
			this.arguments = args;
			this.preparedArguments = args;
		}

		public int getTypeDifferenceWeight(Class<?>[] paramTypes) {
			// If valid arguments found, determine type difference weight.
			// Try type difference weight on both the converted arguments and
			// the raw arguments. If the raw weight is better, use it.
			// Decrease raw weight by 1024 to prefer it over equal converted weight.
			int typeDiffWeight = MethodInvoker.getTypeDifferenceWeight(paramTypes, this.arguments);
			int rawTypeDiffWeight = MethodInvoker.getTypeDifferenceWeight(paramTypes, this.rawArguments) - 1024;
			return Math.min(rawTypeDiffWeight, typeDiffWeight);
		}

		public int getAssignabilityWeight(Class<?>[] paramTypes) {
			for (int i = 0; i < paramTypes.length; i++) {
				if (!ClassUtils.isAssignableValue(paramTypes[i], this.arguments[i])) {
					return Integer.MAX_VALUE;
				}
			}
			for (int i = 0; i < paramTypes.length; i++) {
				if (!ClassUtils.isAssignableValue(paramTypes[i], this.rawArguments[i])) {
					return Integer.MAX_VALUE - 512;
				}
			}
			return Integer.MAX_VALUE - 1024;
		}

		public void storeCache(RootBeanDefinition mbd, Executable constructorOrFactoryMethod) {
			synchronized (mbd.constructorArgumentLock) {
				mbd.resolvedConstructorOrFactoryMethod = constructorOrFactoryMethod;
				mbd.constructorArgumentsResolved = true;
				if (this.resolveNecessary) {
					mbd.preparedConstructorArguments = this.preparedArguments;
				}
				else {
					mbd.resolvedConstructorArguments = this.arguments;
				}
			}
		}
	}


	/**
	 * Delegate for checking Java's {@link ConstructorProperties} annotation.
	 */
	private static class ConstructorPropertiesChecker {

		@Nullable
		public static String[] evaluate(Constructor<?> candidate, int paramCount) {
			ConstructorProperties cp = candidate.getAnnotation(ConstructorProperties.class);
			if (cp != null) {
				String[] names = cp.value();
				if (names.length != paramCount) {
					throw new IllegalStateException("Constructor annotated with @ConstructorProperties but not " +
							"corresponding to actual number of parameters (" + paramCount + "): " + candidate);
				}
				return names;
			}
			else {
				return null;
			}
		}
	}


	/**
	 * DependencyDescriptor marker for constructor arguments,
	 * for differentiating between a provided DependencyDescriptor instance
	 * and an internally built DependencyDescriptor for autowiring purposes.
	 */
	@SuppressWarnings("serial")
	private static class ConstructorDependencyDescriptor extends DependencyDescriptor {

		@Nullable
		private volatile String shortcut;

		public ConstructorDependencyDescriptor(MethodParameter methodParameter, boolean required) {
			super(methodParameter, required);
		}

		public void setShortcut(@Nullable String shortcut) {
			this.shortcut = shortcut;
		}

		public boolean hasShortcut() {
			return (this.shortcut != null);
		}

		@Override
		public Object resolveShortcut(BeanFactory beanFactory) {
			String shortcut = this.shortcut;
			return (shortcut != null ? beanFactory.getBean(shortcut, getDependencyType()) : null);
		}
	}

}
