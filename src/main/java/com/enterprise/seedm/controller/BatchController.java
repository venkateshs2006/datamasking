package com.enterprise.seedm.controller;

import org.springframework.batch.core.*;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
@RestController
@RequestMapping("/api/batch")
public class BatchController {
    @Autowired
    private JobExplorer jobExplorer;

    @GetMapping("/job-execution/{executionId}")
    public JobExecution getJobExecution(@PathVariable Long executionId){
        return jobExplorer.getJobExecution(executionId);
    }
    @GetMapping("/job-execution-histroy/{jobName}")
    public List<JobExecution> getJobExecutionHistory(@PathVariable String jobName){
        List<JobExecution> executions=new ArrayList<>();
        JobParameters jobParameter=new JobParametersBuilder().toJobParameters();
        JobInstance jobInstance=jobExplorer.getJobInstance(jobName,jobParameter);
        if(jobInstance!=null) {
            jobExplorer.getJobExecutions(jobInstance).forEach(executions::add);
        }
        System.out.println("Execution Details :"+executions.toString());
        return executions;
    }
    @GetMapping("/step-execution/{executionId}")
    public List<StepExecution> getStepExecution(@PathVariable Long executionId) {
        List<StepExecution> stepExecutions = new ArrayList<>();
        jobExplorer.getJobExecution(executionId).getStepExecutions().forEach(stepExecutions::add);
        return stepExecutions;
    }
}
