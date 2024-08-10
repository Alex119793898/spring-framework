package org.mytest;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

public class Test {

	public static void main(String[] args) {
		testClassPathXmlApplicationContext();
	}

	public static void  testAnnotationConfigApplicationContext(){
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(JavaConfig.class);
		User user = (User) context.getBean("user");
		System.out.println(user);
	}


	public static void testClassPathXmlApplicationContext(){
		ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext("demo.xml");
		User user = context.getBean(User.class);
		System.out.println(user);
	}
}
