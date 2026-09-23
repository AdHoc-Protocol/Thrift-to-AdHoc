package org.unirail;

import org.unirail.adhoc.AdHocWriter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.unirail.adhoc.AdHocWriter.I1;
import static org.unirail.adhoc.AdHocWriter.I2;
import static org.unirail.adhoc.AdHocWriter.I3;
import static org.unirail.adhoc.AdHocWriter.I4;
import static org.unirail.adhoc.AdHocWriter.doc;
import static org.unirail.adhoc.AdHocWriter.ident;
import static org.unirail.adhoc.AdHocWriter.str;

/**
 * Apache Thrift IDL (.thrift) → AdHoc protocol description (.cs) converter.
 *
 * <p>Usage: <code>java -cp out org.unirail.Thrift2AdHoc &lt;file.thrift | folder&gt; [output folder]</code>
 * (output defaults to <code>&lt;cwd&gt;/AdHoc</code>). One <code>.cs</code> is written per top-level
 * <code>.thrift</code>; every file it <code>include</code>s (transitively) is merged into the same descriptor as a
 * nested <code>struct &lt;fileBase&gt; { … }</code> container, so Thrift's qualified names
 * (<code>shared.SharedStruct</code>) stay valid C# paths.
 *
 * <p>Mapping (see README.md for the full table): struct/union/exception → pack; enum → enum; typedef → TYPEDEF
 * class; const → constants container; service method → <code>&lt;Service&gt;_&lt;method&gt;_Args</code> /
 * <code>_Result</code> packs and an RPC shorthand in the Client↔Server connection; <code>oneway</code> → a
 * fire-and-forget state.
 */
public class Thrift2AdHoc {

	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			System.out.println("Usage: java -cp out org.unirail.Thrift2AdHoc <file.thrift | folder> [output folder]");
			System.out.println("       output folder defaults to <current dir>/AdHoc");
			return;
		}
		Path src = Paths.get(args[0]);
		Path dst = 1 < args.length ? Paths.get(args[1]) : Paths.get(System.getProperty("user.dir"), "AdHoc");
		List<File> files = new ArrayList<>();
		if (Files.isDirectory(src)) {
			File[] list = src.toFile().listFiles((d, n) -> n.endsWith(".thrift"));
			if (list != null) files.addAll(Arrays.asList(list));
		} else files.add(src.toFile());
		if (files.isEmpty()) {
			System.err.println("No .thrift files found in `" + src.toAbsolutePath() + "`.");
			System.exit(1);
			return;
		}
		files.sort(null);
		Files.createDirectories(dst);

		int failed = 0;
		for (File file : files)
			try {
				Map<String, Doc> loaded = new HashMap<>();
				Doc doc = Doc.load(file.toPath(), loaded);
				Emitter em = new Emitter(doc);
				String cs = em.emit();
				Path out = dst.resolve(doc.name + ".cs");
				Files.write(out, cs.getBytes(StandardCharsets.UTF_8));
				System.out.printf("%-24s -> %s  (%d packs, %d enums, %d services, %d rpc, %d oneway; sources: %s)%n",
						file.getName(), out, em.packs, em.enums, em.services, em.rpc, em.oneway, String.join(" ", em.sources));
			} catch (Exception e) {
				failed++;
				System.err.println("FAILED " + file + ": " + e.getMessage());
				e.printStackTrace();
			}
		if (0 < failed) System.exit(2);
	}

	// ═══════════════════════════════════════════ lexer ═══════════════════════════════════════════

	enum K { ID, INT, DBL, STR, PUNCT, EOF }

	static final class Tok {
		K kind;
		String text;
		int line;
		String docBefore = "";  // doc comments accumulated since the previous token
		String trailing = "";   // a comment that starts on this token's line, after it

		public String toString() { return kind + " `" + text + "` (line " + line + ")"; }
	}

	static final class Lexer {
		final String s;
		int i, line = 1;
		final List<Tok> out = new ArrayList<>();
		final StringBuilder docBuf = new StringBuilder();

		Lexer(String s) { this.s = s; }

		List<Tok> run() {
			while (true) {
				skipWsAndComments();
				Tok t = new Tok();
				t.line = line;
				if (i >= s.length()) {
					t.kind = K.EOF;
					t.text = "";
					emit(t);
					return out;
				}
				char c = s.charAt(i);
				if (Character.isLetter(c) || c == '_') {
					int st = i;
					while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_' || s.charAt(i) == '.')) i++;
					t.kind = K.ID;
					t.text = s.substring(st, i);
				} else if (Character.isDigit(c) || (c == '-' || c == '+') && i + 1 < s.length() && (Character.isDigit(s.charAt(i + 1)) || s.charAt(i + 1) == '.')) {
					int st = i;
					i++;
					boolean dbl = false;
					if (c == '0' && i < s.length() && (s.charAt(i) == 'x' || s.charAt(i) == 'X')) {
						i++;
						while (i < s.length() && Character.digit(s.charAt(i), 16) >= 0) i++;
					} else {
						while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.' || s.charAt(i) == 'e' || s.charAt(i) == 'E'
								|| (s.charAt(i) == '-' || s.charAt(i) == '+') && (s.charAt(i - 1) == 'e' || s.charAt(i - 1) == 'E'))) {
							if (s.charAt(i) == '.' || s.charAt(i) == 'e' || s.charAt(i) == 'E') dbl = true;
							i++;
						}
					}
					t.kind = dbl ? K.DBL : K.INT;
					t.text = s.substring(st, i);
				} else if (c == '"' || c == '\'') {
					i++;
					StringBuilder sb = new StringBuilder();
					while (i < s.length() && s.charAt(i) != c) {
						char ch = s.charAt(i++);
						if (ch == '\\' && i < s.length()) {
							char e = s.charAt(i++);
							switch (e) {
								case 'n': sb.append('\n'); break;
								case 't': sb.append('\t'); break;
								case 'r': sb.append('\r'); break;
								default: sb.append(e); break;
							}
						} else {
							if (ch == '\n') line++;
							sb.append(ch);
						}
					}
					i++;
					t.kind = K.STR;
					t.text = sb.toString();
				} else {
					i++;
					t.kind = K.PUNCT;
					t.text = String.valueOf(c);
				}
				emit(t);
			}
		}

		void emit(Tok t) {
			t.docBefore = docBuf.toString().trim();
			docBuf.setLength(0);
			out.add(t);
			lastTokenEnd = i;
		}

		void skipWsAndComments() {
			while (i < s.length()) {
				char c = s.charAt(i);
				if (c == '\n') { line++; i++; }
				else if (Character.isWhitespace(c)) i++;
				else if (c == '#' || c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
					int st = i;
					while (i < s.length() && s.charAt(i) != '\n') i++;
					String text = s.substring(st, i).replaceFirst("^(//|#)\\s?", "");
					comment(text, st);
				} else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
					int st = i;
					int end = s.indexOf("*/", i + 2);
					if (end < 0) end = s.length();
					String body = s.substring(i + 2, end);
					boolean isDoc = s.startsWith("/**", i) && !s.startsWith("/**/", i);
					for (int k = i; k < Math.min(end + 2, s.length()); k++) if (s.charAt(k) == '\n') line++;
					i = Math.min(end + 2, s.length());
					if (isDoc) {
						StringBuilder sb = new StringBuilder();
						for (String l : body.split("\\r?\\n")) {
							l = l.trim();
							if (l.startsWith("*")) l = l.substring(1).trim();
							if (l.equals("*")) continue;
							sb.append(l).append('\n');
						}
						comment(sb.toString(), st);
					}
				} else break;
			}
		}

		/** A comment starting on the same line as the previous token is its trailing doc; otherwise it is a leading doc. */
		void comment(String text, int start) {
			if (!out.isEmpty()) {
				Tok last = out.get(out.size() - 1);
				boolean sameLine = s.lastIndexOf('\n', start) < 0 || s.lastIndexOf('\n', start) < lastTokenEnd;
				if (sameLine && last.kind != K.EOF) {
					last.trailing = (last.trailing + " " + text.trim()).trim();
					return;
				}
			}
			docBuf.append(text.trim()).append('\n');
		}

		int lastTokenEnd = 0;
	}

	// ═══════════════════════════════════════════ model ═══════════════════════════════════════════

	static final class Type {
		String base;          // bool, i8, i16, i32, i64, double, string, binary, uuid, list, set, map, ref
		String ref;           // for base == ref: possibly qualified name (file.Type)
		Type k, v;            // list/set: v; map: k, v
		final List<String[]> annotations = new ArrayList<>();

		static Type of(String base) { Type t = new Type(); t.base = base; return t; }

		String key() {
			switch (base) {
				case "list": return "List_" + v.key();
				case "set": return "Set_" + v.key();
				case "map": return "Map_" + k.key() + "_to_" + v.key();
				case "ref": return ref.replace('.', '_');
				default: return base;
			}
		}
	}

	static final class Field {
		int id = Integer.MIN_VALUE;
		String req = "";      // "", "required", "optional"
		Type type;
		String name, doc = "";
		Object dflt;          // Thrift default value, as parsed; rendered as a constant beside the field
		final List<String[]> annotations = new ArrayList<>();
	}

	static final class Struct {
		String kind, name, doc = "";   // struct | union | exception
		final List<Field> fields = new ArrayList<>();
		final List<String[]> annotations = new ArrayList<>();
	}

	static final class EnumDef {
		String name, doc = "";
		final List<String[]> members = new ArrayList<>(); // {name, value, doc}
	}

	static final class TypedefDef {
		String name, doc = "";
		Type type;
	}

	static final class ConstDef {
		String name, doc = "";
		Type type;
		Object value;
	}

	static final class Function {
		boolean oneway;
		Type ret; // null = void
		String name, doc = "";
		final List<Field> args = new ArrayList<>();
		final List<Field> throwsList = new ArrayList<>();
	}

	static final class Service {
		String name, extendsName, doc = "";
		final List<Function> functions = new ArrayList<>();
	}

	static final class Doc {
		String name, fileName;
		Path path;
		final List<Doc> includes = new ArrayList<>();
		final LinkedHashMap<String, String> namespaces = new LinkedHashMap<>();
		final List<TypedefDef> typedefs = new ArrayList<>();
		final List<ConstDef> consts = new ArrayList<>();
		final List<EnumDef> enums = new ArrayList<>();
		final List<Struct> structs = new ArrayList<>();
		final List<Service> services = new ArrayList<>();
		final List<String> dropped = new ArrayList<>(); // constructs with no AdHoc equivalent, surfaced as comments

		static Doc load(Path path, Map<String, Doc> loaded) throws Exception {
			String fileName = path.getFileName().toString();
			Doc existing = loaded.get(fileName);
			if (existing != null) return existing;
			Doc d = new Doc();
			d.path = path;
			d.fileName = fileName;
			d.name = ident(fileName.endsWith(".thrift") ? fileName.substring(0, fileName.length() - 7) : fileName);
			loaded.put(fileName, d);
			String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
			if (text.startsWith("﻿")) text = text.substring(1);
			new Parser(new Lexer(text).run(), d, loaded).document();
			return d;
		}

		/** The included document a qualified name refers to, by file base name. */
		Doc include(String prefix) {
			for (Doc inc : includes) if (inc.name.equals(ident(prefix)) || inc.fileName.equals(prefix + ".thrift")) return inc;
			return null;
		}
	}

	// ═══════════════════════════════════════════ parser ═══════════════════════════════════════════

	static final class Parser {
		final List<Tok> toks;
		final Doc doc;
		final Map<String, Doc> loaded;
		int p;

		Parser(List<Tok> toks, Doc doc, Map<String, Doc> loaded) { this.toks = toks; this.doc = doc; this.loaded = loaded; }

		Tok peek() { return toks.get(p); }
		Tok peek(int n) { return toks.get(Math.min(p + n, toks.size() - 1)); }
		Tok next() { return toks.get(p++); }
		boolean is(String punctOrId) { Tok t = peek(); return (t.kind == K.PUNCT || t.kind == K.ID) && t.text.equals(punctOrId); }
		boolean accept(String s) { if (is(s)) { p++; return true; } return false; }

		Tok expect(String s) {
			if (!is(s)) throw err("`" + s + "` expected");
			return next();
		}

		Tok expectId() {
			if (peek().kind != K.ID) throw err("identifier expected");
			return next();
		}

		RuntimeException err(String msg) { return new IllegalStateException(doc.fileName + ":" + peek().line + ": " + msg + ", got " + peek()); }

		void listSeparator() { if (!accept(",")) accept(";"); }

		void document() throws Exception {
			while (peek().kind != K.EOF) {
				Tok t = peek();
				if (t.kind != K.ID) throw err("definition expected");
				switch (t.text) {
					case "include": {
						next();
						String inc = next().text;
						Path p1 = doc.path.resolveSibling(inc);
						if (!Files.exists(p1)) p1 = doc.path.resolveSibling(Paths.get(inc).getFileName().toString()); // "share/fb303/if/fb303.thrift" → fb303.thrift next to us
						if (!Files.exists(p1)) throw new IllegalStateException(doc.fileName + ":" + t.line + ": included file `" + inc + "` not found next to " + doc.path);
						doc.includes.add(Doc.load(p1, loaded));
						break;
					}
					case "cpp_include": next(); next(); break;
					case "namespace": {
						next();
						String scope = next().text;
						String name = next().text;
						doc.namespaces.put(scope, name);
						annotations(null);
						break;
					}
					case "php_namespace":
					case "xsd_namespace": next(); next(); break;
					case "const": constant(t.docBefore); break;
					case "typedef": typedef(t.docBefore); break;
					case "enum": enumDef(t.docBefore); break;
					case "senum": { next(); String sn = next().text; expect("{"); while (!accept("}")) next(); doc.dropped.add("senum " + sn + " - deprecated Thrift string enum, no AdHoc equivalent"); break; }
					case "struct":
					case "union":
					case "exception": struct(t.text, t.docBefore); break;
					case "service": service(t.docBefore); break;
					default: throw err("unknown definition `" + t.text + "`");
				}
			}
		}

		void annotations(List<String[]> into) {
			if (!is("(")) return;
			next();
			while (!accept(")")) {
				String k = expectId().text;
				String v = "";
				if (accept("=")) v = next().text;
				if (into != null) into.add(new String[]{k, v});
				listSeparator();
			}
		}

		Type type() {
			Tok t = next();
			Type r;
			switch (t.text) {
				case "list": {
					expect("<");
					r = Type.of("list");
					r.v = type();
					expect(">");
					if (accept("cpp_type")) next();
					break;
				}
				case "set": {
					if (accept("cpp_type")) next();
					expect("<");
					r = Type.of("set");
					r.v = type();
					expect(">");
					break;
				}
				case "map": {
					if (accept("cpp_type")) next();
					expect("<");
					r = Type.of("map");
					r.k = type();
					expect(",");
					r.v = type();
					expect(">");
					break;
				}
				case "bool": case "byte": case "i8": case "i16": case "i32": case "i64": case "double":
				case "string": case "binary": case "uuid": case "slist":
					r = Type.of(t.text.equals("byte") ? "i8" : t.text.equals("slist") ? "string" : t.text);
					break;
				default:
					if (t.kind != K.ID) throw err("type expected");
					r = Type.of("ref");
					r.ref = t.text;
			}
			annotations(r.annotations); // type annotations, e.g. set<i32> (python.immutable = "")
			return r;
		}

		Object constValue() {
			Tok t = next();
			switch (t.kind) {
				case INT: return Long.decode(t.text.startsWith("+") ? t.text.substring(1) : t.text);
				case DBL: return Double.parseDouble(t.text);
				case STR: return t.text;
				case ID: return new Ident(t.text);
				default:
					if (t.text.equals("[")) {
						List<Object> l = new ArrayList<>();
						while (!accept("]")) { l.add(constValue()); listSeparator(); }
						return l;
					}
					if (t.text.equals("{")) {
						LinkedHashMap<Object, Object> m = new LinkedHashMap<>();
						while (!accept("}")) {
							Object k = constValue();
							expect(":");
							m.put(k, constValue());
							listSeparator();
						}
						return m;
					}
					throw err("constant value expected");
			}
		}

		void constant(String docText) {
			next();
			ConstDef c = new ConstDef();
			c.doc = docText;
			c.type = type();
			c.name = expectId().text;
			expect("=");
			c.value = constValue();
			listSeparator();
			doc.consts.add(c);
		}

		void typedef(String docText) {
			next();
			TypedefDef td = new TypedefDef();
			td.doc = docText;
			td.type = type();
			td.name = expectId().text;
			annotations(null);
			listSeparator();
			doc.typedefs.add(td);
		}

		void enumDef(String docText) {
			next();
			EnumDef e = new EnumDef();
			e.doc = docText;
			e.name = expectId().text;
			expect("{");
			long nextValue = 0;
			while (!is("}")) {
				Tok n = expectId();
				long v = nextValue;
				if (accept("=")) v = Long.decode(next().text);
				nextValue = v + 1;
				List<String[]> ann = new ArrayList<>();
				annotations(ann);
				Tok last = toks.get(p - 1);
				listSeparator();
				String md = n.docBefore;
				String tr = toks.get(p - 1).trailing.isEmpty() ? last.trailing : toks.get(p - 1).trailing;
				if (!tr.isEmpty()) md = (md + "\n" + tr).trim();
				e.members.add(new String[]{n.text, Long.toString(v), md});
			}
			expect("}");
			annotations(null);
			doc.enums.add(e);
		}

		Field field() {
			Field f = new Field();
			Tok first = peek();
			if (peek().kind == K.INT && peek(1).text.equals(":")) {
				f.id = Integer.parseInt(next().text);
				next();
			}
			if (is("required") || is("optional")) f.req = next().text;
			f.type = type();
			accept("&"); // reference marker (cpp)
			f.name = expectId().text;
			if (accept("=")) f.dflt = constValue();
			while (is("xsd_optional") || is("xsd_nillable")) next();
			if (is("xsd_attrs")) { next(); expect("{"); while (!accept("}")) next(); }
			annotations(f.annotations);
			Tok last = toks.get(p - 1);
			listSeparator();
			Tok sep = toks.get(p - 1);
			String d = first.docBefore;
			String tr = !sep.trailing.isEmpty() ? sep.trailing : last.trailing;
			if (!tr.isEmpty()) d = (d + "\n" + tr).trim();
			f.doc = d;
			return f;
		}

		void struct(String kind, String docText) {
			next();
			Struct st = new Struct();
			st.kind = kind;
			st.doc = docText;
			st.name = expectId().text;
			accept("xsd_all");
			expect("{");
			while (!is("}")) st.fields.add(field());
			expect("}");
			annotations(st.annotations);
			doc.structs.add(st);
		}

		void service(String docText) {
			next();
			Service sv = new Service();
			sv.doc = docText;
			sv.name = expectId().text;
			if (accept("extends")) sv.extendsName = expectId().text;
			expect("{");
			while (!is("}")) {
				Function fn = new Function();
				Tok first = peek();
				fn.doc = first.docBefore;
				if (accept("oneway") || accept("async")) fn.oneway = true;
				if (accept("void")) fn.ret = null;
				else fn.ret = type();
				fn.name = expectId().text;
				expect("(");
				while (!is(")")) fn.args.add(field());
				expect(")");
				if (accept("throws")) {
					expect("(");
					while (!is(")")) fn.throwsList.add(field());
					expect(")");
				}
				annotations(null);
				listSeparator();
				sv.functions.add(fn);
			}
			expect("}");
			annotations(null);
			doc.services.add(sv);
		}
	}

	static final class Ident {
		final String name;
		Ident(String n) { name = n; }
		public String toString() { return name; }
	}

	/** Text rendering of a constant value (for comments on skipped constants and doc notes). */
	@SuppressWarnings("unchecked")
	static String render(Object v) {
		if (v instanceof String) return "\"" + v + "\"";
		if (v instanceof List) {
			StringBuilder sb = new StringBuilder("[");
			for (Object o : (List<Object>) v) sb.append(sb.length() > 1 ? ", " : "").append(render(o));
			return sb.append("]").toString();
		}
		if (v instanceof Map) {
			StringBuilder sb = new StringBuilder("{");
			for (Map.Entry<Object, Object> e : ((Map<Object, Object>) v).entrySet())
				sb.append(sb.length() > 1 ? ", " : "").append(render(e.getKey())).append(": ").append(render(e.getValue()));
			return sb.append("}").toString();
		}
		return String.valueOf(v);
	}

	// ═══════════════════════════════════════════ emitter ═══════════════════════════════════════════

	static final class Emitter {
		final Doc root;
		final StringBuilder sb = new StringBuilder(1 << 16);
		final List<String> sources = new ArrayList<>();
		final List<Doc> order = new ArrayList<>();                       // includes first, root last
		final LinkedHashMap<String, Integer> dashboard = new LinkedHashMap<>();
		final LinkedHashMap<String, String> wrapperPacks = new LinkedHashMap<>(); // Wrap_<key> → inner C# type
		final Set<String> enumPaths = new HashSet<>();                  // C# paths of enums (value types)
		final Map<String, Type> typedefPaths = new HashMap<>();         // C# path of a typedef → aliased type
		final Set<String> structPaths = new HashSet<>();
		final Set<String> emptyStructPaths = new HashSet<>();           // structs without fields → presence flags
		final Map<String, String> onewayOwners = new LinkedHashMap<>(); // Args pack -> the state that carries it
		int packs, enums, services, rpc, oneway;

		Emitter(Doc root) {
			this.root = root;
			collect(root, new HashSet<>());
			for (Doc d : order) {
				sources.add(d.fileName);
				String prefix = d == root ? "" : d.name + ".";
				for (EnumDef e : d.enums) enumPaths.add(prefix + ident(e.name));
				for (TypedefDef t : d.typedefs) typedefPaths.put(prefix + ident(t.name), t.type);
				for (Struct s : d.structs) {
					structPaths.add(prefix + ident(s.name));
					if (s.fields.isEmpty()) emptyStructPaths.add(prefix + ident(s.name));
				}
			}
		}

		void collect(Doc d, Set<Doc> seen) {
			if (!seen.add(d)) return;
			for (Doc inc : d.includes) collect(inc, seen);
			order.add(d);
		}

		String emit() {
			StringBuilder body = new StringBuilder();
			for (Doc d : order) {
				if (d == root) continue;
				body.append('\n').append(I2).append("// ═════════════════════════ include \"").append(d.fileName).append("\" ═════════════════════════\n");
				body.append(I2).append("public struct ").append(d.name).append(" {\n");
				definitions(body, d, I3, d.name + ".");
				body.append(I2).append("}\n");
			}
			body.append('\n').append(I2).append("// ═════════════════════════ ").append(root.fileName).append(" ═════════════════════════\n");
			definitions(body, root, I2, "");

			// wrapper packs for Set/Map/uuid nested inside a Set or Map, created on demand while emitting fields
			if (!wrapperPacks.isEmpty()) {
				body.append('\n').append(I2).append("// ═════════════════════════ wrappers: a Set/Map/uuid nested inside a Set or Map ═════════════════════════\n\n");
				for (Map.Entry<String, String> e : wrapperPacks.entrySet()) {
					String inner = e.getValue();
					String attr = "";
					if (inner.startsWith("[D(16)] ")) { attr = "[D(16)] "; inner = inner.substring(8); }
					body.append(I2).append("public class ").append(e.getKey()).append(" { ").append(attr).append(inner).append(" value; }\n");
					dashboard.put(e.getKey(), null);
				}
			}

			topology(body);
			attributes(body);

			// assemble
			List<String> ns = new ArrayList<>();
			for (Doc d : order) for (Map.Entry<String, String> e : d.namespaces.entrySet()) ns.add(d.fileName + ": namespace " + e.getKey() + " " + e.getValue());
			AdHocWriter.fileHeader(sb, "Thrift2AdHoc", String.join(", ", sources), ns.toArray(new String[0]));
			sb.append("namespace org.thrift {\n");
			AdHocWriter.dashboard(sb, I1, dashboard);
			sb.append(I1).append("public interface ").append(root.name).append(" {\n");
			sb.append(I2).append("// A trailing `// N:` on a field is its Thrift field id; `<field>_default` is the field's Thrift default.\n");
			sb.append(I2).append("// Neither is read by AdHoc.\n");
			sb.append(I2).append("// Thrift declares no size bounds. These permissive defaults replace AdHoc's 255 limit;\n");
			sb.append(I2).append("// add [D(+N)] on a field whose real bound is known.\n");
			sb.append(I2).append("enum _DefaultMaxLengthOf { Arrays = 65_535, Maps = 65_535, Sets = 65_535, Strings = 65_535, }\n");
			sb.append(body);
			sb.append(I1).append("}\n");
			sb.append("}\n");
			return sb.toString();
		}

		// ───────────────────────────── definitions of one document ─────────────────────────────

		void definitions(StringBuilder b, Doc d, String ind, String prefix) {
			String ind1 = ind + I1;
			Set<String> taken = new HashSet<>();

			// constants
			List<String> constLines = new ArrayList<>();
			for (ConstDef c : d.consts) {
				String line = constant(c, ind1, d, prefix);
				if (line != null) constLines.add(line);
			}
			if (!constLines.isEmpty()) {
				b.append('\n').append(ind).append("/** Thrift `const` declarations of ").append(d.fileName).append(" (non-transmittable constants container). */\n");
				b.append(ind).append("public struct Consts {\n");
				for (String l : constLines) b.append(l);
				b.append(ind).append("}\n");
				taken.add("Consts");
			}

			// typedefs
			for (TypedefDef t : d.typedefs) {
				b.append('\n');
				doc(b, ind, t.doc);
				String name = ident(t.name);
				b.append(ind).append("public class ").append(name).append(" { ").append(typedefBody(t.type, prefix, d)).append(" TYPEDEF; }\n");
			}

			// enums
			for (EnumDef e : d.enums) {
				b.append('\n');
				String name = ident(e.name);
				if (e.members.size() < 2) {
					b.append(ind).append("// Thrift enum with fewer than two members: AdHoc rejects such enums, kept as a constants container.\n");
					doc(b, ind, e.doc);
					b.append(ind).append("public struct ").append(name).append(" {\n");
					for (String[] m : e.members) {
						doc(b, ind1, m[2]);
						b.append(ind1).append("public const int ").append(memberName(name, m[0])).append(" = ").append(m[1]).append(";\n");
					}
					if (e.members.isEmpty()) b.append(ind1).append("public const bool EMPTY = true; // the Thrift enum declares no members\n");
					b.append(ind).append("}\n");
					continue;
				}
				enums++;
				doc(b, ind, e.doc);
				boolean wide = false;
				for (String[] m : e.members) { long v = Long.parseLong(m[1]); if (v < Integer.MIN_VALUE || Integer.MAX_VALUE < v) wide = true; }
				b.append(ind).append("enum ").append(name).append(wide ? " : long" : "").append(" {\n");
				for (String[] m : e.members) {
					doc(b, ind1, m[2]);
					b.append(ind1).append(memberName(name, m[0])).append(" = ").append(m[1]).append(",\n");
				}
				b.append(ind).append("}\n");
			}

			// structs / unions / exceptions
			for (Struct s : d.structs) {
				b.append('\n');
				String name = ident(s.name);
				String docText = s.doc;
				if (s.kind.equals("union")) docText = (docText.isEmpty() ? "" : docText + "\n") + "Thrift union: exactly one of the fields is set.";
				if (s.kind.equals("exception")) docText = (docText.isEmpty() ? "" : docText + "\n") + "Thrift exception.";
				for (String[] a : s.annotations) docText += "\nannotation " + a[0] + " = \"" + a[1] + "\"";
				doc(b, ind, docText);
				b.append(ind).append("public class ").append(name).append(" {\n");
				Set<String> fieldNames = new HashSet<>();
				for (Field f : s.fields) {
					doc(b, ind1, fieldDoc(f, prefix, d));
					b.append(ind1).append(field(f, ind1, prefix, d, name, fieldNames, s.kind.equals("union"))).append('\n');
				}
				b.append(ind).append("}\n");
				dashboard.put(prefix + name, null);
				packs++;
			}

			// services → request / result packs (the RPC declarations go into the connection)
			for (Service sv : d.services) {
				services++;
				String sname = ident(sv.name);
				b.append('\n').append(ind).append("// ───── packs of service ").append(sv.name).append(sv.extendsName != null ? " extends " + sv.extendsName : "").append(" ─────\n");
				for (Function fn : sv.functions) {
					String base = sname + "_" + ident(fn.name);
					b.append('\n');
					doc(b, ind, (fn.doc.isEmpty() ? "" : fn.doc + "\n") + "Arguments of " + sv.name + "." + fn.name + "().");
					b.append(ind).append("public class ").append(base).append("_Args {\n");
					Set<String> fieldNames = new HashSet<>();
					for (Field f : fn.args) {
						doc(b, ind1, fieldDoc(f, prefix, d));
						b.append(ind1).append(field(f, ind1, prefix, d, base + "_Args", fieldNames, false)).append('\n');
					}
					b.append(ind).append("}\n");
					dashboard.put(prefix + base + "_Args", null);
					packs++;
					if (!fn.oneway) {
						b.append('\n');
						doc(b, ind, "Result of " + sv.name + "." + fn.name + "()" + (fn.ret == null ? " (void)." : "."));
						b.append(ind).append("public class ").append(base).append("_Result {\n");
						if (fn.ret != null) {
							Field r = new Field();
							r.type = fn.ret;
							r.name = "success";
							r.id = 0;
							doc(b, ind1, fieldDoc(r, prefix, d));
							b.append(ind1).append(field(r, ind1, prefix, d, base + "_Result", new HashSet<>(), false)).append('\n');
						}
						b.append(ind).append("}\n");
						dashboard.put(prefix + base + "_Result", null);
						packs++;
					}
				}
			}
		}

		static String memberName(String enumName, String member) {
			String m = ident(member);
			return m.equals(enumName) ? m + "_value" : m;
		}

		String constant(ConstDef c, String ind, Doc d, String prefix) {
			StringBuilder b = new StringBuilder();
			doc(b, ind, c.doc);
			String name = ident(c.name);
			Object v = c.value;
			// effective type: follow typedef aliases; remember when the type is an enum
			Type eff = c.type;
			String enumPath = null;
			for (int guard = 0; guard < 8 && eff.base.equals("ref"); guard++) {
				String path = resolve(eff.ref, prefix, d);
				Type aliased = typedefPaths.get(path);
				if (aliased != null) eff = aliased;
				else { if (enumPaths.contains(path)) enumPath = path; break; }
			}
			String t = enumPath != null ? "enum:" + enumPath : eff.base;
			if (v instanceof List) {
				String elem = eff.base.equals("list") || eff.base.equals("set") ? csConstType(eff.v) : null;
				if (elem == null || elem.equals("object")) {
					b.append(ind).append("// const ").append(c.name).append(" = ").append(render(v)).append(" — container constant without a C# literal form, skipped\n");
					return b.toString();
				}
				StringBuilder items = new StringBuilder();
				for (Object o : (List<?>) v) items.append(items.length() > 0 ? ", " : "").append(literal(o, elem, d));
				b.append(ind).append("public static ").append(elem).append("[] ").append(name).append(" = { ").append(items).append(" };\n");
				return b.toString();
			}
			if (v instanceof Map) {
				b.append(ind).append("// const ").append(c.name).append(" = ").append(render(v)).append(" — map constant has no C# literal form, skipped\n");
				return b.toString();
			}
			if (t.startsWith("enum:")) {
				// AdHoc constants cannot have an enum type: emit the member's numeric value and name the member in a comment
				String path = t.substring(5);
				String member = v instanceof Ident ? ((Ident) v).name : String.valueOf(v);
				Long value = enumMemberValue(path, member.substring(member.lastIndexOf('.') + 1));
				if (value == null && v instanceof Long) value = (Long) v;
				if (value == null) {
					b.append(ind).append("// const ").append(c.name).append(" = ").append(member).append(" — enum member not found, skipped\n");
					return b.toString();
				}
				b.append(ind).append("public const int ").append(name).append(" = ").append(value).append("; // ").append(path).append('.').append(member.substring(member.lastIndexOf('.') + 1)).append('\n');
				return b.toString();
			}
			String cs = csConstType(eff);
			if (cs.equals("object") || t.equals("ref")) {
				b.append(ind).append("// const ").append(c.name).append(" = ").append(render(v)).append(" — no constant form for this type, skipped\n");
				return b.toString();
			}
			b.append(ind).append("public const ").append(cs).append(' ').append(name).append(" = ").append(literal(v, cs, d)).append(';');
			if (!cs.equals(csScalar(eff))) b.append(" // Thrift ").append(eff.base).append(", widened: AdHocAgent rejects narrow signed constants");
			b.append('\n');
			return b.toString();
		}

		/** Numeric value of an enum member, looked up by the enum's C# path. */
		Long enumMemberValue(String enumPath, String member) {
			for (Doc d : order) {
				String prefix = d == root ? "" : d.name + ".";
				for (EnumDef e : d.enums)
					if ((prefix + ident(e.name)).equals(enumPath))
						for (String[] m : e.members) if (m[0].equals(member)) return Long.parseLong(m[1]);
			}
			return null;
		}

		String literal(Object v, String cs, Doc d) {
			if (v instanceof String) return str((String) v);
			if (v instanceof Ident) {
				String n = ((Ident) v).name;
				// `true` / `false` reach here as identifiers; brushing them as C# keywords would give `True`
				if (n.equals("true") || n.equals("false")) return n;
				// a reference to another const or enum member: keep it as a path, brushing every segment
				StringBuilder sb = new StringBuilder();
				for (String seg : n.split("\\.")) sb.append(sb.length() > 0 ? "." : "").append(ident(seg));
				return sb.toString();
			}
			if (v instanceof Double) {
				double x = (Double) v;
				if (cs.equals("double")) return Double.toString(x);
				return Long.toString((long) x);
			}
			if (v instanceof Long) {
				if (cs.equals("double")) return v + ".0";
				if (cs.equals("bool")) return ((Long) v) != 0 ? "true" : "false";
				return String.valueOf(v);
			}
			if (v instanceof Boolean) return String.valueOf(v);
			return str(render(v));
		}

		/** C# scalar for a base type, or "object" when the type is not a scalar. */
		/**
		 * Type of a `const` declaration. AdHocAgent crashes on `const sbyte` / `const short`
		 * (InvalidCastException in ConstantImpl.init_exT), so narrow signed constants widen to int; the value is
		 * unchanged and the original Thrift type is noted in a trailing comment.
		 */
		String csConstType(Type t) {
			String cs = csScalar(t);
			return cs.equals("sbyte") || cs.equals("short") ? "int" : cs;
		}

		String csScalar(Type t) {
			switch (t.base) {
				case "bool": return "bool";
				case "i8": return "sbyte";
				case "i16": return "short";
				case "i32": return "int";
				case "i64": return "long";
				case "double": return "double";
				case "string": return "string";
				default: return "object";
			}
		}

		// ───────────────────────────── types ─────────────────────────────

		/** Resolves a (possibly qualified) Thrift type name to its C# path within the descriptor. */
		String resolve(String ref, String prefix, Doc d) {
			int dot = ref.indexOf('.');
			if (dot > 0) {
				String file = ref.substring(0, dot), name = ref.substring(dot + 1);
				Doc inc = d.include(file);
				if (inc != null) return inc.name + "." + ident(name);
				return ident(file) + "." + ident(name); // best effort
			}
			return prefix + ident(ref);
		}

		boolean isValueType(String path) {
			if (enumPaths.contains(path)) return true;
			Type aliased = typedefPaths.get(path);
			if (aliased == null) return false;
			switch (aliased.base) {
				case "bool": case "i8": case "i16": case "i32": case "i64": case "double": return true;
				case "ref": return isValueType(aliased.ref.contains(".") ? aliased.ref : path.substring(0, path.lastIndexOf('.') + 1) + ident(aliased.ref));
				default: return false;
			}
		}

		/**
		 * C# type for a field declared at pack level. Verified against the agent: a list may hold anything
		 * ({@code int[,,][,,]}, {@code Set<int>[,,]}, {@code Map<..>[,,]}, {@code Binary[,,][,,]}); Set elements and
		 * Map keys/values may be scalars, packs, typedefs or arrays ({@code Map<Binary[,,], int[,,]>}) but not another
		 * Set/Map - those are wrapped in a one-field pack.
		 */
		String topType(Type t, String prefix, String docName) {
			Doc d = docOf(docName);
			switch (t.base) {
				case "list": return elemType(t.v, prefix, d, true) + "[,,]";
				case "set": return "Set<" + elemType(t.v, prefix, d, false) + ">";
				case "map": return "Map<" + elemType(t.k, prefix, d, false) + ", " + elemType(t.v, prefix, d, false) + ">";
				case "binary": return "Binary[,,]";
				case "uuid": return "[D(16)] Binary[]";
				case "ref": return resolve(t.ref, prefix, d);
				default: return csScalar(t);
			}
		}

		Doc docOf(String name) {
			for (Doc d : order) if (d.name.equals(name)) return d;
			return root;
		}

		/**
		 * C# type usable inside a container. Limits verified against the agent's field parser: a plain field takes at
		 * most two array levels ({@code X[,,][,,]}); a Set/Map head takes one ({@code Set<X>[,,]}); a Set/Map slot
		 * takes a named type or a one-level array of one ({@code Map<K, X[,,]>}) but no Set/Map. Anything deeper is
		 * wrapped in a one-field pack.
		 */
		String elemType(Type t, String prefix, Doc d, boolean listElement) {
			if (t.base.equals("uuid")) return wrapper(t, prefix, d);
			if (t.base.equals("ref")) {
				String path = resolve(t.ref, prefix, d);
				Type aliased = typedefPaths.get(path);
				// a typedef of a container cannot be nested inside another container: inline the aliased type
				if (aliased != null && !aliased.base.equals("ref") && csScalar(aliased).equals("object")) {
					Doc td = docOfPath(path);
					String tprefix = td == root ? "" : td.name + ".";
					return elemType(aliased, tprefix, td, listElement);
				}
				return path;
			}
			String cs = topType(t, prefix, d.name);
			boolean generic = cs.startsWith("Set<") || cs.startsWith("Map<");
			int levels = arrayLevels(cs);
			boolean tooDeep = listElement ? (generic ? 1 <= levels : 2 <= levels) : (generic || 2 <= levels);
			return tooDeep ? wrapper(t, prefix, d) : cs;
		}

		/** Number of trailing {@code [...]} groups of a C# type string. */
		static int arrayLevels(String cs) {
			int n = 0;
			String s = cs.trim();
			while (s.endsWith("]")) {
				int open = s.lastIndexOf('[');
				if (open < 0) break;
				s = s.substring(0, open).trim();
				n++;
			}
			return n;
		}

		Doc docOfPath(String path) {
			int dot = path.indexOf('.');
			if (dot < 0) return root;
			return docOf(path.substring(0, dot));
		}

		/** A one-field pack wrapping a Set/Map/uuid so it can sit inside a Set or a Map. */
		String wrapper(Type t, String prefix, Doc d) {
			String name = "Wrap_" + ident(t.key());
			if (!wrapperPacks.containsKey(name)) {
				wrapperPacks.put(name, null); // reserve first: resolving the inner type may recurse into this map
				wrapperPacks.put(name, typedefBody(t, prefix, d));
			}
			return name;
		}

		/** Field doc plus a note when the Thrift type is an empty struct (AdHoc transmits such a field as a presence flag). */
		String fieldDoc(Field f, String prefix, Doc d) {
			String empty = emptyStruct(f.type, prefix, d);
			if (empty == null) return f.doc;
			return (f.doc.isEmpty() ? "" : f.doc + "\n") + "Thrift type " + empty + " is an empty struct: carried as a bool presence flag.";
		}

		/** The C# path of the empty struct the type refers to, or null. */
		String emptyStruct(Type t, String prefix, Doc d) {
			if (!t.base.equals("ref")) return null;
			String path = resolve(t.ref, prefix, d);
			return emptyStructPaths.contains(path) ? path : null;
		}

		/**
		 * Attributes describing the distribution of a number, per the source's own encoding. Thrift's Compact
		 * protocol encodes i16/i32/i64 as ZigZag varint, so those carry {@code [X]} - AdHoc's two-sided varint.
		 * i8, double, bool, string and binary are fixed-width in Compact and carry nothing.
		 * {@code targeted} receives {@code Key: X} / {@code Val: X} forms, which each need their own bracket.
		 */
		void varint(Type t, String prefix, Doc d, List<String> attrs, List<String> targeted) {
			switch (t.base) {
				case "i16": case "i32": case "i64": attrs.add("X"); break;
				case "list": if (isZigZag(t.v, prefix, d)) attrs.add("X"); break;
				case "set": if (isZigZag(t.v, prefix, d)) targeted.add("Key: X"); break;
				case "map":
					if (isZigZag(t.k, prefix, d)) targeted.add("Key: X");
					if (isZigZag(t.v, prefix, d)) targeted.add("Val: X");
					break;
				default: break; // a ref carries the attribute inside its own TYPEDEF, per the AdHoc README
			}
		}

		/**
		 * Only a literal i16/i32/i64 earns the attribute at the use site. A TYPEDEF carries its own, and the
		 * agent propagates it to every field that uses the alias - `[X]` on the alias-typed field is rejected.
		 */
		boolean isZigZag(Type t, String prefix, Doc d) {
			switch (t.base) {
				case "i16": case "i32": case "i64": return true;
				default: return false;
			}
		}

		/**
		 * A note on the field when its own name or documentation says the values are systematically large,
		 * uniformly spread, or pinned to a floor - cases where the ZigZag varint inherited from Thrift Compact
		 * costs more than a fixed-width field. Varint wins only while the typical distance from the base stays
		 * under about two million and always loses past 268,435,455, so the decision belongs to whoever knows the
		 * data. The converter never changes the attribute, it only asks the question at the place it matters.
		 */
		String physics(Field f) {
			switch (f.type.base) {
				case "i16": case "i32": case "i64": break;
				default: return null;
			}
			String n = f.name.toLowerCase();
			String text = (f.name + " " + f.doc).toLowerCase();
			boolean wide = f.type.base.equals("i64");

			if (n.matches(".*(hash|checksum|crc|digest|fingerprint|signature|guid|uuid|salt|nonce|seed).*"))
				return "physics: an unpredictable value spread over the whole range - [X] adds a byte to every packet, drop it";

			if (wide && (n.matches(".*(time|timestamp|_ts|date|epoch|expir|deadline|created|modified|accessed|updated|since|until|ttl).*")
					|| text.contains("epoch") || text.contains("since the unix") || text.contains("milliseconds since") || text.contains("seconds since")))
				return "physics: looks like a wall-clock time, always far above 2^28 - [X] costs a byte per packet; drop it, or model the instant as DateTime";

			if (n.matches(".*(offset|position|size|length|count|num|total|bytes|len)$|^(offset|size|length|count|total)$")
					|| n.matches(".*(sequence|seqno|seq|index|version|generation|revision|serial|txnid|writeid|rowid|lsn)$"))
				return "physics: a counter or an offset - it only grows, so [A] (clustered at the floor) fits better than [X]";

			if (n.matches("^(id|key)$|.*_(id|key)$") && wide)
				return "physics: an identifier - if it is monotonic and already past 268,435,455, [X] is a permanent loss; drop it or use [A]";

			return null;
		}

		/** Attributes plus type for a TYPEDEF or wrapper field: the varint attribute belongs on the alias itself. */
		String typedefBody(Type t, String prefix, Doc d) {
			List<String> attrs = new ArrayList<>();
			List<String> targeted = new ArrayList<>();
			String type = topType(t, prefix, d.name);
			if (type.startsWith("[D(16)] ")) { attrs.add("D(16)"); type = type.substring(8); }
			varint(t, prefix, d, attrs, targeted);
			StringBuilder b = new StringBuilder();
			for (String x : targeted) b.append('[').append(x).append("] ");
			if (!attrs.isEmpty()) b.append('[').append(String.join(", ", attrs)).append("] ");
			return b.append(type).toString();
		}

		/**
		 * One field of a pack, and the constant that carries its Thrift default when it has one.
		 *
		 * Only what AdHoc acts on goes into attributes. Three pieces of Thrift metadata used to ride along as
		 * attributes the generator knows nothing about, and each now has a home that is not an attribute:
		 *  - the field id is a trailing comment in Thrift's own notation, {@code // 3:} - it exists to find the
		 *    field in the .thrift file, nothing in AdHoc reads it;
		 *  - {@code required} is gone: an AdHoc value type is mandatory unless it is {@code T?}, and a reference
		 *    type is optional by nature, so the qualifier had nothing left to say;
		 *  - the default value is a {@code const} named {@code <field>_default} in the same pack, rendered by the
		 *    same rules as a Thrift {@code const} (enum members become their number, containers are skipped
		 *    with a comment).
		 * Annotations stay as {@code [Annotation]}: they are Thrift-side metadata the reader may want intact.
		 *
		 * The returned text starts after {@code ind} on the field's line and ends without a newline; the caller
		 * adds it, so a default's extra line begins with {@code ind} itself.
		 */
		String field(Field f, String ind, String prefix, Doc d, String packName, Set<String> taken, boolean union) {
			List<String> attrs = new ArrayList<>();
			List<String> targeted = new ArrayList<>();
			boolean empty = emptyStruct(f.type, prefix, d) != null;
			String type = empty ? "bool" : topType(f.type, prefix, d.name);
			if (type.startsWith("[D(16)] ")) { attrs.add("D(16)"); type = type.substring(8); }
			boolean valueType = !type.contains("[") && !type.startsWith("Set<") && !type.startsWith("Map<") && !type.equals("string")
					&& (csScalar(f.type).equals(type) || type.equals("bool") || isValueType(type));
			boolean optional = union || f.req.equals("optional");
			if (optional && valueType) type += "?";

			if (!empty) varint(f.type, prefix, d, attrs, targeted);
			for (String[] a : f.annotations) attrs.add("Annotation(" + str(a[0]) + ", " + str(a[1]) + ")");
			for (String[] a : f.type.annotations) attrs.add("Annotation(" + str(a[0]) + ", " + str(a[1]) + ")");

			String name = ident(f.name);
			if (name.equals(packName)) name = name + "_field";
			String base = name;
			for (int i = 2; taken.contains(name); i++) name = base + i;
			taken.add(name);

			StringBuilder b = new StringBuilder();
			String note = physics(f);
			if (note != null) b.append("// ").append(note).append('\n').append(ind);
			for (String t : targeted) b.append('[').append(t).append("] ");
			if (!attrs.isEmpty()) b.append('[').append(String.join(", ", attrs)).append("] ");
			b.append(type).append(' ').append(name).append(';');
			if (f.id != Integer.MIN_VALUE) b.append(" // ").append(f.id).append(':');

			if (f.dflt != null) {
				ConstDef c = new ConstDef();
				c.name = name + "_default";
				c.type = f.type;
				c.value = f.dflt;
				taken.add(c.name);
				String line = constant(c, ind, d, prefix);   // ends with '\n'; the caller supplies the line break
				b.append('\n').append(line, 0, line.length() - 1);
			}
			return b.toString();
		}

		// ───────────────────────────── hosts, connection, RPC ─────────────────────────────

		void topology(StringBuilder b) {
			b.append('\n').append(I2).append("// ═════════════════════════ topology ═════════════════════════\n\n");
			AdHocWriter.host(b, I2, "Client", "Calls the services (Left side).");
			AdHocWriter.host(b, I2, "Server", "Implements the services (Right side).");

			List<Service> all = new ArrayList<>();
			for (Doc d : order) all.addAll(d.services);
			if (all.isEmpty()) {
				b.append(I2).append("// No services in the source: every pack may be sent by either side.\n");
				AdHocWriter.connectionAllPacks(b, I2, "ClientServer", "Client", "Server", root.name);
				return;
			}

			b.append(I2).append("interface ClientServer : Connects<Client, Server> {\n");
			StringBuilder onewayStates = new StringBuilder();
			for (Doc d : order) {
				String prefix = d == root ? "" : d.name + ".";
				for (Service sv : d.services) {
					String sname = ident(sv.name);
					b.append('\n');
					String docText = sv.doc;
					if (sv.extendsName != null) docText = (docText.isEmpty() ? "" : docText + "\n") + "extends " + sv.extendsName + ": its methods are declared below as well.";
					doc(b, I3, "Thrift service " + sv.name + (docText.isEmpty() ? "" : "\n" + docText));
					b.append(I3).append("interface ").append(sname).append(" {\n");
					rpcMethods(b, sv, prefix, d, new HashSet<>(), sname, onewayStates);
					b.append(I3).append("}\n");
				}
			}
			if (0 < onewayStates.length()) {
				b.append('\n').append(I3).append("// oneway methods: fire-and-forget states of the connection's default actor\n");
				b.append(onewayStates);
			}
			b.append(I2).append("}\n\n");
		}

		/** RPC shorthand for every method, including inherited ones (their packs belong to the base service). */
		void rpcMethods(StringBuilder b, Service sv, String prefix, Doc d, Set<String> emitted, String owner, StringBuilder onewayStates) {
			String sname = ident(sv.name);
			for (Function fn : sv.functions) {
				String mname = ident(fn.name);
				if (!emitted.add(mname)) continue;
				String base = prefix + sname + "_" + mname;
				if (fn.oneway) {
					// One state per Args pack. An inherited oneway method reuses the base service's pack, and the
					// same pack may not appear twice in the always-active scope of the connection's default actor.
					String state = onewayOwners.get(base);
					if (state == null) {
						oneway++;
						state = owner + "_" + mname;
						onewayOwners.put(base, state);
						onewayStates.append(I3).append("[l____________<").append(base).append("_Args>]\n");
						onewayStates.append(I3).append("struct ").append(state).append(" { }\n");
					}
					b.append(I4).append("// oneway ").append(fn.name).append("(): see state ").append(state).append(" of the connection\n");
					continue;
				}
				rpc++;
				List<String> results = new ArrayList<>();
				results.add("L____________");
				results.add(base + "_Result");
				for (Field t : fn.throwsList) {
					String path = topType(t.type, prefix, d.name);
					if (!results.contains(path)) results.add(path);
				}
				b.append(I4).append("(").append(String.join(", ", results)).append(") ").append(mname).append("(").append(base).append("_Args req);\n");
			}
			if (sv.extendsName != null) {
				Found parent = findService(sv.extendsName, d);
				if (parent == null) b.append(I4).append("// base service ").append(sv.extendsName).append(" not found\n");
				else {
					Doc pd = parent.doc;
					String pprefix = pd == root ? "" : pd.name + ".";
					b.append(I4).append("// inherited from ").append(sv.extendsName).append('\n');
					rpcMethods(b, parent.service, pprefix, pd, emitted, owner, onewayStates);
				}
			}
		}

		static final class Found { Service service; Doc doc; }

		Found findService(String ref, Doc d) {
			int dot = ref.indexOf('.');
			Doc target = d;
			String name = ref;
			if (dot > 0) {
				target = d.include(ref.substring(0, dot));
				name = ref.substring(dot + 1);
				if (target == null) return null;
			}
			for (Service s : target.services) if (s.name.equals(name)) { Found f = new Found(); f.service = s; f.doc = target; return f; }
			return null;
		}

		// ───────────────────────────── custom attributes ─────────────────────────────

		void attributes(StringBuilder b) {
			b.append(I2).append("// ═════════════════════════ Thrift metadata attribute ═════════════════════════\n\n");
			b.append(I2).append("// Not read by AdHoc; kept so a Thrift annotation survives the conversion.\n\n");
			b.append(I2).append("/** Thrift annotation `(key = \"value\")` on the field or its type. */\n");
			b.append(I2).append("[AttributeUsage(AttributeTargets.All, AllowMultiple = true)]\n");
			b.append(I2).append("public class AnnotationAttribute : Attribute { public AnnotationAttribute(string key, string value) { } }\n");
		}
	}
}
