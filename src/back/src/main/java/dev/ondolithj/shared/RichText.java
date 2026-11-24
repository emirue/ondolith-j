package dev.ondolithj.shared;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

/** 리치 텍스트 정화(1-C1 스펙 3절). 저장 때 forSave, 내보낼 때 clean. 프런트 DOMPurify 와 같은 벡터 표를 쓴다. */
public final class RichText {

	public static final int MAX = 200_000;

	// 스펙 3.2. addProtocols·preserveRelativeLinks 를 쓰지 않는다 — 값은 아래 값 검사가 본다
	private static final Safelist SAFELIST = new Safelist()
		.addTags("p", "br", "strong", "em", "h2", "h3", "ul", "ol", "li", "blockquote", "a", "img")
		.addAttributes("a", "href")
		.addAttributes("img", "src", "alt")
		.addAttributes("ol", "start");

	// 모두 matches()(전체 일치)로 본다. find() 의 $ 는 끝 개행 앞에서도 맞는다
	private static final Pattern MEDIA = Pattern
		.compile("/api/media/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

	private static final Pattern START = Pattern.compile("[0-9]{1,6}");

	private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\\n\\r\\t]]");

	// SiteName 과 같다. NBSP·U+200B 도 보이지 않는 문자다
	private static final Pattern INVISIBLE = Pattern.compile("[\\p{Z}\\p{C}]*");

	private RichText() {
	}

	public sealed interface Result permits Ok, Bad {
	}

	public record Ok(String html) implements Result {
	}

	public record Bad(String message) implements Result {
	}

	/** 예외를 던지지 않는다(DB 에 직접 들어간 옛 행에도). */
	public static String clean(String html) {
		// 허용 밖 껍데기가 빠지면 <p>a<p>b</p></p> 처럼 파서가 만들 수 없는 꼴이 남아 다시 파싱하면 모양이 바뀐다.
		// 바뀌지 않을 때까지(고정점) 다시 건다 — 두 번으로 모자란 입력이 있다(1-C1 최종 검토 I-1, 무작위 50만 개 중 203개가 세 번).
		// 모든 통과 결과는 이미 정화된 값이라, 상한에 닿아도 안전은 그대로이고 멱등만 깨진다.
		// ponytail: 상한 8 — 그보다 오래 바뀌는 입력이 나오면 상한을 올리거나 그 꼴을 시험에 더한다
		if (html == null) {
			return "";
		}
		String out = once(html);
		for (int i = 1; i < 8; i++) {
			String next = once(out);
			if (next.equals(out)) {
				return out;
			}
			out = next;
		}
		return out;
	}

	private static String once(String html) {
		Document doc = new Cleaner(SAFELIST).clean(Jsoup.parseBodyFragment(html));
		// select 는 반복으로 걷는다 — 깊은 중첩에도 재귀가 없다
		for (Element img : doc.select("img")) {
			if (!MEDIA.matcher(img.attr("src")).matches()) {
				img.remove();
			}
		}
		// 검사기는 jsoup 이 엔티티를 푼 값을 본다(&#x6A;avascript: 는 javascript: 로). 못 지나면 href 만 지우고 글자는 남긴다
		for (Element a : doc.select("a[href]")) {
			if (!LinkCheck.ok(a.attr("href"))) {
				a.removeAttr("href");
			}
		}
		for (Element ol : doc.select("ol[start]")) {
			if (!START.matcher(ol.attr("start")).matches()) {
				ol.removeAttr("start");
			}
		}
		doc.outputSettings().prettyPrint(false).charset(StandardCharsets.UTF_8);
		return doc.body().html();
	}

	public static Result forSave(String raw) {
		if (raw == null) {
			return new Bad("필수입니다");
		}
		if (raw.length() > MAX) {
			return new Bad("200,000자 이하");
		}
		if (CONTROL.matcher(raw).find()) {
			return new Bad("쓸 수 없는 문자가 있습니다");
		}
		String out = clean(raw);
		if (out.length() > MAX) {
			return new Bad("200,000자 이하");
		}
		// jsoup 은 &#8; 을 &#x8; 로 다시 써서 출력 문자열에는 제어 문자가 보이지 않는다 — 엔티티를 푼 값으로 본다
		if (CONTROL.matcher(Parser.unescapeEntities(out, false)).find()) {
			return new Bad("쓸 수 없는 문자가 있습니다");
		}
		Element body = Jsoup.parseBodyFragment(out).body();
		if (INVISIBLE.matcher(body.text()).matches() && body.select("img").isEmpty()) {
			return new Bad("본문을 입력하세요");
		}
		return new Ok(out);
	}
}
