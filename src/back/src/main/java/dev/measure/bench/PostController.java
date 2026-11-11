package dev.measure.bench;

import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/posts")
class PostController {
	private final PostRepository repo;

	PostController(PostRepository repo) { this.repo = repo; }

	@GetMapping
	List<Post> list(@RequestParam(defaultValue = "0") int page) {
		return repo.findAll(PageRequest.of(page, 20, Sort.by("id").descending())).getContent();
	}

	@GetMapping("/{id}")
	ResponseEntity<Post> one(@PathVariable long id) {
		return ResponseEntity.of(repo.findById(id));
	}
}
