package org.mytest.myFactoryBean;

import org.springframework.context.support.ClassPathXmlApplicationContext;

public class TestMyFactoryBean {

	public static void main(String[] args) {
		ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext("factoryBean.xml");

		User user = (User) context.getBean("myFactoryBean");
		System.out.println(user.getName());

		MyFactoryBean myFactoryBean = (MyFactoryBean) context.getBean("&myFactoryBean");
		System.out.println(myFactoryBean);
	}
}
