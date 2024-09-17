package org.mytest.constructorAutowired;

import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

public class Test {

    public static void main(String[] args) {

        ApplicationContext ac = new ClassPathXmlApplicationContext("constructorAutowiredTest.xml");
        Person bean = ac.getBean(Person.class);
        Person bean2 = ac.getBean(Person.class);


    }
}
