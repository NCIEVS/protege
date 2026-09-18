package org.protege.editor.owl.server.http.messages;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class History implements Serializable {
	/**
	 * 
	 */
	private static final long serialVersionUID = -4521102352676041770L;
	
	private LocalDateTime start_date = null;
	private LocalDateTime end_date = null;
	
	public LocalDateTime getStartDate() { return start_date; }
	public LocalDateTime getEndDate() { return end_date; }
	
	
	private String date;
	public String getDate() { return date; }
	private String user_name = null;
	public String getUser_name() { return user_name; }
	private String code = null;
	public String getCode() { return code; }
	private String name;
	public String getName() { return name; }
	private String operation = null;
	public String getOperation() { return operation; }
	public String getOp() { return operation; }
	private String reference;
	public String getReference() { return reference; }
	
	public enum HistoryType {EVS, CONCEPT};
	
	public void setQueryArgs(String start, String end, String user, String code, String op) {
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
		
		if (!start.isEmpty()) {
			start_date = LocalDateTime.parse(start, formatter);
		}
		if (!end.isEmpty()) {
			end_date = LocalDateTime.parse(end, formatter);
		}
		if (!user.isEmpty()) {
			user_name = user;
		}
		if (!code.isEmpty()) {
			this.code = code;
		}
		operation = op;		
	}
	
	public static History createConHist(String[] tokens) {
		// ignore date when using EVS history for concept history
		String un = tokens[1];
		String code = tokens[2];
		String name = tokens[3];
		String op = tokens[4];
		String ref = null;
		if (tokens.length == 6) {		
			ref = tokens[5];
		}
		return new History(un, code, name, op, ref);
		
	}
	
	public static int cnt = 0;
	
	public static History createEvsHist(String[] tokens) {
		
		String date = tokens[0];
		String un = tokens[1];
		String code = tokens[2];
		String name = tokens[3];
		String op = null;
		if (tokens.length >= 5) {
			op = tokens[4];			
		}
		
		String ref = null;
		if (tokens.length == 6) {		
			ref = tokens[5];
		}
		return new History(date, un, code, name, op, ref);
		
	}
	
	public History(String un, String c, String n, String op, String ref) {
		this.date = formatNow();
		user_name = un;
		code = c;
		name = n;
		operation = op;
		reference = ref;
		
	}
	
	public History(String date, String un, String c, String n, String op, String ref) {
		this.date = date;
		user_name = un;
		code = c;
		name = n;
		operation = op;
		reference = ref;
		
	}
	
	
	
	public History() {
		// TODO Auto-generated constructor stub
	}
	private static String formatNow() {
		LocalDateTime date = LocalDateTime.now();
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
		return date.format(formatter);
	}
	
	
	
	public String toRecord(HistoryType type) {
		if (type == HistoryType.EVS) {
			StringBuilder sb = new StringBuilder(date + "\t" +
					user_name + "\t" +
					code + "\t" +
					name + "\t" +
					operation + "\t");
			if (reference != null) {
				sb.append(reference);
			}
			return sb.toString();
		} else {
			return code + "\t" +
					operation + "\t" +
					date + "\t" +
					reference;
		}

	}

	// Round-trippable encoding for carrying a revision's EVS records inside the change-log metadata
	// (one tab-delimited EVS record per line), so the log is the single source for replaying them.
	public static String encodeEvsList(List<History> records) {
		StringBuilder sb = new StringBuilder();
		for (History h : records) {
			if (sb.length() > 0) {
				sb.append('\n');
			}
			sb.append(h.toRecord(HistoryType.EVS));
		}
		return sb.toString();
	}

	public static List<History> decodeEvsList(String encoded) {
		List<History> result = new ArrayList<>();
		if (encoded == null || encoded.isEmpty()) {
			return result;
		}
		for (String line : encoded.split("\n")) {
			if (line.isEmpty()) {
				continue;
			}
			result.add(createEvsHist(line.split("\t")));
		}
		return result;
	}

}
