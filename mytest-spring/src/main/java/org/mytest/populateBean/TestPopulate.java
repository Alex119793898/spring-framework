package org.mytest.populateBean;

import org.springframework.context.support.ClassPathXmlApplicationContext;

public class TestPopulate {

    public static void main(String[] args) {
//        ClassPathXmlApplicationContext ac = new ClassPathXmlApplicationContext("populateBean1.xml");
        ClassPathXmlApplicationContext ac = new ClassPathXmlApplicationContext("populateBean2.xml");
        ac.close();
    }
}
