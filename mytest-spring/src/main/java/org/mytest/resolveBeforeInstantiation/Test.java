package org.mytest.resolveBeforeInstantiation;

import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

public class Test {

	public static void main(String[] args) {
		ApplicationContext ac = new ClassPathXmlApplicationContext("resolveBeforeInstantiantion.xml");
		BeforeInstantiantion bean = ac.getBean(BeforeInstantiantion.class);
		System.out.println(bean);
	}
}
