package com.example.oemintel.web;

import java.util.List;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.example.oemintel.catalog.CatalogQueries;
import com.example.oemintel.job.JobException;

@Controller
class ProductController {

	private final CatalogQueries catalog;

	private final JsonMapper json;

	ProductController(CatalogQueries catalog, JsonMapper json) {
		this.catalog = catalog;
		this.json = json;
	}

	@GetMapping("/products")
	String products(Model model) {
		model.addAttribute("families", this.catalog.families());
		return "products";
	}

	@GetMapping("/products/{id}")
	String product(@PathVariable long id, Model model) {
		CatalogQueries.FamilyDetail detail = this.catalog.family(id)
			.orElseThrow(() -> JobException.notFound("No product family " + id));
		model.addAttribute("p", detail);
		String warnings = detail.header().warningsJson();
		model.addAttribute("warnings",
				(warnings == null) ? List.of() : this.json.readValue(warnings, new TypeReference<List<String>>() {
				}));
		return "product";
	}

}
