package org.mytest.context_scan_biaoqian;

import org.springframework.stereotype.Component;

@Component
public class Person {
	private String id;
	private String username;

	public Person() {
	}

	public Person(String id, String username) {
		this.id = id;
		this.username = username;
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