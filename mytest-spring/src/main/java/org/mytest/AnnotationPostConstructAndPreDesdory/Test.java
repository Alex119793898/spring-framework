package org.mytest.AnnotationPostConstructAndPreDesdory;

import org.mytest.annotationtest.JavaConfig;
import org.mytest.context_scan_biaoqian.Person;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

public class Test {

	public static void main(String[] args) {

		ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext("demo.xml");
		User user = context.getBean(User.class);
		System.out.println(user);
	}

}
