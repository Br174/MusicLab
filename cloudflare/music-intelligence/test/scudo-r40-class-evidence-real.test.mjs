import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,mkdirSync,writeFileSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {run} from '../../../scripts/scudo360-gate.mjs';

function evidenceFixture(actualName='example.CoverTest') {
  const root=mkdtempSync(join(tmpdir(),'scudo-r40-real-class-'));
  mkdirSync(join(root,'tests'),{recursive:true});
  writeFileSync(join(root,'tests','mock.js'),'// fixture');
  const manifest={schemaVersion:1,project:'musiclab',
    suites:[{id:'native-jvm',command:['node','tests/mock.js'],paths:['tests/mock.js'],
      junitDir:'test-results',timeoutMs:10000}],
    contracts:[{id:'musiclab.cover.test',priority:'P1',requiredAt:['lab','promotion'],
      suites:['native-jvm'],junitClasses:['example.CoverTest']}]};
  const spawn=()=>{
    mkdirSync(join(root,'test-results'),{recursive:true});
    writeFileSync(join(root,'test-results','TEST-example.CoverTest.xml'),
      '<testsuite name="'+actualName+'" tests="2" failures="0" errors="0" skipped="0">'
      +'<testcase name="one" classname="'+actualName+'"/>'
      +'<testcase name="two" classname="'+actualName+'"/></testsuite>');
    return {status:0,stdout:'',stderr:''};
  };
  try{return run(manifest,{root,level:'lab',spawn})}
  finally{rmSync(root,{recursive:true,force:true})}
}

test('exact fresh native JUnit class is credited to its actual Scudo contract',()=>{
  const report=evidenceFixture();
  assert.equal(report.passed,true);
  assert.deepEqual(report.contracts[0].classEvidence.map(x=>[x.name,x.passed,x.tests]),
    [['example.CoverTest',true,2]]);
});

test('a spoofed class name is never certified even if whole suite is green',()=>{
  const report=evidenceFixture('example.UnrelatedTest');
  assert.equal(report.passed,true);
  assert.equal(report.contracts[0].classEvidence[0].passed,false);
});
