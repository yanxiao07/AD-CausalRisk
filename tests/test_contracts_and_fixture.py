import csv
import json
from pathlib import Path
import unittest
from openapi_spec_validator import validate
from jsonschema import Draft202012Validator,FormatChecker

ROOT=Path(__file__).resolve().parents[1]


class ContractTests(unittest.TestCase):
    def test_both_openapi_documents_are_valid_and_operations_unique(self):
        for name,count in [('python-agent.openapi.json',3),('java-business.target.openapi.json',22)]:
            spec=json.loads((ROOT/'contracts'/name).read_text(encoding='utf-8'));validate(spec)
            operations=[value for path in spec['paths'].values() for method,value in path.items() if method in ('get','post','patch')]
            self.assertEqual(len(operations),count)
            self.assertEqual(len({operation['operationId'] for operation in operations}),count)
    def test_java_target_has_explicit_implementation_status(self):
        spec=json.loads((ROOT/'contracts/java-business.target.openapi.json').read_text(encoding='utf-8'))
        self.assertIn(spec['x-implementation-state'],['PLANNED_NOT_IMPLEMENTED_IN_BASELINE','IMPLEMENTED_BY_TEAM'])
        for path in spec['paths'].values():
            for operation in path.values():self.assertIn(operation['x-implementation-state'],['TO_IMPLEMENT_BY_BACKEND_OWNER','IMPLEMENTED_BY_BACKEND_OWNER'])
    def test_fixture_has_100_unique_synthetic_subjects_and_shared_version(self):
        fixture=json.loads((ROOT/'fixtures/synthetic_subjects_100.json').read_text(encoding='utf-8'))
        self.assertEqual(fixture['dataSource'],'SYNTHETIC_DEMO')
        subjects=fixture['subjects'];self.assertEqual(len(subjects),100)
        self.assertEqual(len({r['subjectCode'] for r in subjects}),100)
        schema=json.loads((ROOT/'contracts/python-agent.openapi.json').read_text(encoding='utf-8'))['components']['schemas']['AgentRequest']
        validator=Draft202012Validator(schema,format_checker=FormatChecker())
        for row in subjects:
            self.assertTrue(row['subjectCode'].startswith('DEMO-'));self.assertEqual(row['dataVersion'],fixture['dataVersion'])
            validator.validate(row)
    def test_synthetic_csv_row_counts_and_keys(self):
        for name,expected in [('subjects.synthetic.csv',100),('visits.synthetic.csv',200),('features.synthetic.csv',300)]:
            with (ROOT/'fixtures'/name).open(encoding='utf-8',newline='') as stream:rows=list(csv.DictReader(stream))
            self.assertEqual(len(rows),expected)
            self.assertTrue(all(row['subjectCode'].startswith('DEMO-') for row in rows))
    def test_patient_result_schema_cannot_include_raw_agent_score(self):
        spec=json.loads((ROOT/'contracts/java-business.target.openapi.json').read_text(encoding='utf-8'))
        schema=spec['components']['schemas']['PatientAssessmentSummary'];validator=Draft202012Validator(schema)
        value={'assessmentId':1,'summary':'合成摘要','followUpSummary':'待复核','limitations':['课程演示']}
        validator.validate(value)
        with self.assertRaises(Exception):validator.validate({**value,'riskScore':.5})


if __name__=='__main__':unittest.main()
