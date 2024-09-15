package org.mytest.resolveBeforeInstantiation;

import org.springframework.beans.BeansException;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;
import org.springframework.cglib.proxy.Enhancer;

import java.beans.PropertyDescriptor;

public class MyInstantiantionAwareBeanPostProcessor implements InstantiationAwareBeanPostProcessor {


	@Override
	public Object postProcessBeforeInstantiation(Class<?> beanClass, String beanName) throws BeansException {
		System.out.println("beanName:" + beanName + "----执行postProcessBeforeInstantiation方法");
		if(beanClass == BeforeInstantiantion.class){
//			Enhancer enhancer = new Enhancer();
//			enhancer.setSuperclass(BeforeInstantiantion.class);
//			enhancer.setCallback(new MyMethodInterceptor());
//			BeforeInstantiantion beforeInstantiantion = (BeforeInstantiantion) enhancer.create();
//			System.out.println("创建代理对象：" + beforeInstantiantion);
//			return beforeInstantiantion;
			return new BeforeInstantiantion();
		}
		return null;
	}

	@Override
	public boolean postProcessAfterInstantiation(Object bean, String beanName) throws BeansException {
		System.out.println("beanName:" + beanName + "----执行postProcessAfterInstantiation方法");
		return false;
	}

	@Override
	public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
		System.out.println("beanName:" + beanName + "----执行postProcessBeforeInitialization方法");
		return bean;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		System.out.println("beanName:" + beanName + "----执行postProcessAfterInitialization方法");
		return bean;
	}

	@Override
	public PropertyValues postProcessProperties(PropertyValues pvs, Object bean, String beanName) throws BeansException {
		System.out.println("beanName:" + beanName + "----执行postProcessProperties方法");
		return pvs;
	}

//	@Override
//	public PropertyValues postProcessPropertyValues(PropertyValues pvs, PropertyDescriptor[] pds, Object bean, String beanName) throws BeansException {
//		return InstantiationAwareBeanPostProcessor.super.postProcessPropertyValues(pvs, pds, bean, beanName);
//	}
}
