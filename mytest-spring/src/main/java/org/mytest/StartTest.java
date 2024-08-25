package org.mytest;

import org.mytest.annotationtest.JavaConfig;
import org.mytest.context_scan_biaoqian.Person;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

public class StartTest {

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
		Person person = context.getBean(Person.class);
		System.out.println(person);
	}
}
