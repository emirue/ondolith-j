package dev.ondolithj.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.ondolithj.shared.RichText.Bad;
import dev.ondolithj.shared.RichText.Ok;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** 서버 정화기(1-C1 스펙 3절). 벡터 표 13.2 는 프런트 시험도 같은 파일을 읽는다. */
class RichTextTest {

	record Vector(String id, String input, String server) {
	}

	record EditorRow(String id, String input, String editor) {
	}

	static final List<Vector> VECTORS = load("/richtext/vectors.json", new TypeReference<>() {
	});

	// 1-C2 스펙 5.1. 프런트 RichEditor.test 가 같은 파일로 편집기 출력을 본다
	static final List<EditorRow> EDITOR_ROWS = load("/richtext/editor.json", new TypeReference<>() {
	});

	// 스펙 3.2 표. 구현의 SAFELIST 를 읽지 않고 표에서 따로 적는다
	static final Map<String, Set<String>> ALLOWED = Map.ofEntries(Map.entry("p", Set.of()), Map.entry("br", Set.of()),
			Map.entry("strong", Set.of()), Map.entry("em", Set.of()), Map.entry("h2", Set.of()),
			Map.entry("h3", Set.of()), Map.entry("ul", Set.of()), Map.entry("ol", Set.of("start")),
			Map.entry("li", Set.of()), Map.entry("blockquote", Set.of()), Map.entry("a", Set.of("href")),
			Map.entry("img", Set.of("src", "alt")));

	static final Pattern MEDIA = Pattern
		.compile("/api/media/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

	static final Pattern HREF = Pattern.compile("^(https?://|/(?![/\\\\]))");

	static final String U = "00000000-0000-4000-8000-000000000001";

	static <T> List<T> load(String file, TypeReference<List<T>> type) {
		try (InputStream in = RichTextTest.class.getResourceAsStream(file)) {
			return JsonMapper.builder().build().readValue(in, type);
		}
		catch (java.io.IOException e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	void 벡터_표의_모든_줄이_server_와_바이트까지_같다() {
		assertThat(VECTORS).hasSize(96);
		for (Vector v : VECTORS) {
			assertThat(RichText.clean(v.input())).as(v.id()).isEqualTo(v.server());
		}
	}

	@Test
	void 편집기_고정_파일의_editor_는_clean_해도_같다() {
		assertThat(EDITOR_ROWS).hasSize(13);
		for (EditorRow r : EDITOR_ROWS) {
			assertThat(RichText.clean(r.editor())).as(r.id()).isEqualTo(r.editor());
		}
	}

	@Test
	void 모든_출력은_허용_목록만_남는다() {
		for (Vector v : VECTORS) {
			String out = RichText.clean(v.input());
			assertThat(violations(out)).as(v.id() + " → " + out).isEmpty();
		}
	}

	static List<String> violations(String out) {
		List<String> bad = new ArrayList<>();
		for (Element e : Jsoup.parseBodyFragment(out).body().select("*")) {
			if (e.tagName().equals("body")) {
				continue;
			}
			Set<String> attrs = ALLOWED.get(e.tagName());
			if (attrs == null) {
				bad.add("요소 " + e.tagName());
				continue;
			}
			for (Attribute a : e.attributes()) {
				if (!attrs.contains(a.getKey()) || a.getKey().startsWith("on")) {
					bad.add(e.tagName() + "[" + a.getKey() + "]");
				}
			}
			if (e.tagName().equals("img") && !MEDIA.matcher(e.attr("src")).matches()) {
				bad.add("img src " + e.attr("src"));
			}
			// 스펙 13.2 프런트 불변식의 href 꼴. 구현의 LinkCheck 를 부르지 않고 따로 적는다
			if (e.hasAttr("href") && !HREF.matcher(e.attr("href")).find()) {
				bad.add("a href " + e.attr("href"));
			}
		}
		String lower = out.toLowerCase(java.util.Locale.ROOT);
		for (String s : new String[] { "javascript:", "<script", "<style" }) {
			if (lower.contains(s)) {
				bad.add(s);
			}
		}
		return bad;
	}

	@Test
	void 멱등이다() {
		for (Vector v : VECTORS) {
			String once = RichText.clean(v.input());
			assertThat(RichText.clean(once)).as(v.id()).isEqualTo(once);
			assertThat(RichText.clean(v.server())).as(v.id()).isEqualTo(v.server());
		}
		Vector a1 = VECTORS.stream().filter(v -> v.id().equals("A1")).findFirst().orElseThrow();
		assertThat(RichText.clean(a1.input())).isEqualTo(a1.input());
	}

	@Test
	void 빠진_껍데기가_자동_닫힘을_막던_꼴도_멱등이다() {
		// 한 번만 걸면 <p>a<p>b</p></p> 처럼 파서가 만들 수 없는 꼴이 나와, 다시 파싱하면 모양이 바뀐다(구현 때 관찰)
		for (String in : new String[] { "<p>a<object><p>b</p></object></p>",
				"<ul><li>a<section><li>b</li></section></li></ul>",
				"<a href=\"/a\">x<object><a href=\"/b\">y</a></object></a>", "<h2>a<div><h2>b</h2></div></h2>" }) {
			String once = RichText.clean(in);
			assertThat(RichText.clean(once)).as(in).isEqualTo(once);
			assertThat(violations(once)).as(in).isEmpty();
		}
	}

	@Test
	void 세_번째_통과에서야_안정하는_꼴도_멱등이다() {
		// 1-C1 최종 검토 I-1: 두 번 걸면 아직 모양이 바뀌는 입력(무작위 50만 개 중 203개, 모두 세 번이면 안정)
		for (String in : new String[] { "<h3><a><h2><noscript><a>x<br>" }) {
			String out = RichText.clean(in);
			assertThat(RichText.clean(out)).as(in).isEqualTo(out);
			assertThat(violations(out)).as(in).isEmpty();
		}
	}

	@Test
	void null_과_빈_값은_빈_문자열이고_던지지_않는다() {
		assertThat(RichText.clean(null)).isEmpty();
		assertThat(RichText.clean("")).isEmpty();
	}

	@Test
	void 깊은_중첩_200000자도_던지지_않는다() {
		String deep = "<em>".repeat(40_000);
		long t = System.nanoTime();
		assertThatCode(() -> RichText.clean(deep)).doesNotThrowAnyException();
		System.out.println("깊은 중첩 <em>×40000 clean: " + (System.nanoTime() - t) / 1_000_000 + " ms");
	}

	@Test
	void 길이_검사를_지나는_가장_깊은_본문도_작은_스택에서_Ok() throws InterruptedException {
		// 199,999자 — 정화 뒤 닫는 태그가 붙어도 상한 안이라 forSave 의 text()·select 까지 간다
		String deep = "<em>".repeat(22_222) + "a";
		Object[] got = new Object[1];
		Thread th = new Thread(null, () -> {
			try {
				got[0] = RichText.forSave(deep);
			}
			catch (Throwable e) {
				got[0] = e;
			}
		}, "small-stack", 512 * 1024);
		th.start();
		th.join(30_000);
		assertThat(th.isAlive()).isFalse();
		assertThat(got[0]).isInstanceOf(Ok.class);
	}

	@Test
	void forSave_길이와_제어_문자() {
		assertThat(RichText.forSave(null)).isEqualTo(new Bad("필수입니다"));
		assertThat(RichText.forSave("a".repeat(200_001))).isEqualTo(new Bad("200,000자 이하"));
		assertThat(RichText.forSave("a".repeat(200_000))).isEqualTo(new Ok("a".repeat(200_000)));
		assertThat(RichText.forSave("<p>a\u0000</p>")).isEqualTo(new Bad("쓸 수 없는 문자가 있습니다"));
		assertThat(RichText.forSave("<p>a\u0008</p>")).isEqualTo(new Bad("쓸 수 없는 문자가 있습니다"));
		assertThat(RichText.forSave("<p>a\u0085</p>")).isEqualTo(new Bad("쓸 수 없는 문자가 있습니다"));
		assertThat(RichText.forSave("<p>a\n\r\tb</p>")).isInstanceOf(Ok.class);
		// 정화 뒤 &lt; 로 네 배가 된다 — 검사하지 않으면 DB CHECK 위반이 500 이다
		assertThat(RichText.forSave("<".repeat(200_000))).isEqualTo(new Bad("200,000자 이하"));
	}

	@Test
	void forSave_엔티티로_쓴_제어_문자는_푼_값으로_본다() {
		// jsoup 은 &#0; 을 U+FFFD 로 바꾸고(DB 에 NUL 이 가지 않는다), &#8; 은 &#x8; 로 다시 써서
		// 출력 문자열에 제어 문자가 보이지 않는다 — 엔티티를 푼 값으로 막는다
		assertThat(RichText.forSave("<p>a&#8;</p>")).isEqualTo(new Bad("쓸 수 없는 문자가 있습니다"));
		assertThat(RichText.forSave("<p>&#8;</p>")).isEqualTo(new Bad("쓸 수 없는 문자가 있습니다"));
		assertThat(RichText.forSave("<p>a&#127;</p>")).isEqualTo(new Bad("쓸 수 없는 문자가 있습니다"));
		assertThat(RichText.forSave("<p>a&#129;</p>")).isEqualTo(new Bad("쓸 수 없는 문자가 있습니다"));
		assertThat(RichText.forSave("<img src=\"/api/media/" + U + "\" alt=\"&#8;\">"))
			.isEqualTo(new Bad("쓸 수 없는 문자가 있습니다"));
		assertThat(RichText.forSave("<p>&#0;</p>")).isEqualTo(new Ok("<p>�</p>"));
		assertThat(RichText.forSave("<img src=\"/api/media/" + U + "\" alt=\"&#0;\">"))
			.isEqualTo(new Ok("<img src=\"/api/media/" + U + "\" alt=\"�\">"));
		// 글자로 쓴 엔티티 이름은 제어 문자가 아니다
		assertThat(RichText.forSave("<p>&amp;#8;</p>")).isEqualTo(new Ok("<p>&amp;#8;</p>"));
		// 개행·탭 엔티티는 받는다
		assertThat(RichText.forSave("<p>a&#10;&#9;b</p>")).isInstanceOf(Ok.class);
	}

	@Test
	void forSave_보이지_않는_본문은_본문을_입력하세요() {
		for (String in : new String[] { "", "<p> </p>", "<p>&nbsp;</p>", "<p>​</p>", "<script>x</script>",
				"<img src=\"https://evil.example/x.png\">" }) {
			assertThat(RichText.forSave(in)).as(in).isEqualTo(new Bad("본문을 입력하세요"));
		}
		String img = "<img src=\"/api/media/" + U + "\">";
		assertThat(RichText.forSave(img)).isEqualTo(new Ok(img));
		assertThat(RichText.forSave("<p>a</p><script>x</script>")).isEqualTo(new Ok("<p>a</p>"));
	}

}
