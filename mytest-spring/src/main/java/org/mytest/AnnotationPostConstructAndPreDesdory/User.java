package org.mytest.AnnotationPostConstructAndPreDesdory;

import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

@Component
public class User {
	private String id;
	private String username;

	public User() {
	}

	public User(String id, String username) {
		this.id = id;
		this.username = username;
	}

	@PostConstruct
	public void init(){
		System.out.println("init..... ");
	}

	@PreDestroy
	public void destroy(){
		System.out.println("destroy..... ");
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	@Override
	public String toString() {
		return "User{" +
				"id='" + id + '\'' +
				", username='" + username + '\'' +
				'}';
	}
}
