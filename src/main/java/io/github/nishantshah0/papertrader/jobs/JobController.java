package io.github.nishantshah0.papertrader.jobs;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class JobController {

    private final Jobs jobs;

    JobController(Jobs jobs) {
        this.jobs = jobs;
    }

    @GetMapping("/api/jobs/{id}")
    Jobs.Job job(@PathVariable long id) {
        return jobs.get(id);
    }
}
