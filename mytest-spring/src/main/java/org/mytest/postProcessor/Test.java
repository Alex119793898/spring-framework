package org.mytest.postProcessor;

import org.mytest.User;

public class Test {
	public static void main(String[] args) {
		MyClasspathXmlApplicationContext my = new MyClasspathXmlApplicationContext("demo.xml");
		MyXmlBeanFactoryPostProcessor postProcessor = (MyXmlBeanFactoryPostProcessor) my.getBean("xmlPostProcessor");
		System.out.println(postProcessor);
	}
}
