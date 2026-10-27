from tools.carddemo.findings import Finding, apply_baseline, load_baseline, write_baseline


def _f(rule="CD020", path="app/cbl/A.cbl", key="MAIN", value=None):
    return Finding(rule, path, 10, "message", key, value)


def test_new_known_and_stale():
    baseline = [
        {"rule": "CD020", "path": "app/cbl/A.cbl", "key": "MAIN"},
        {"rule": "CD031", "path": "app/cpy/OLD.cpy", "key": "OLD"},
    ]
    result = apply_baseline([_f(), _f(key="OTHER")], baseline)
    assert [f.key for f in result.new] == ["OTHER"]
    assert [f.key for f in result.known] == ["MAIN"]
    assert result.stale == [baseline[1]]


def test_metric_cannot_grow_past_baseline():
    baseline = [{"rule": "CD020", "path": "app/cbl/A.cbl", "key": "MAIN", "value": 30}]
    assert apply_baseline([_f(value=30)], baseline).new == []
    worse = apply_baseline([_f(value=31)], baseline).new
    assert len(worse) == 1 and "baseline allows 30" in worse[0].message


def test_baseline_round_trip(tmp_path):
    path = tmp_path / "baseline.json"
    write_baseline([_f(value=40), _f(rule="CD031", path="app/cpy/X.cpy", key="X")], path)
    entries = load_baseline(path)
    assert entries == [
        {"rule": "CD020", "path": "app/cbl/A.cbl", "key": "MAIN", "value": 40},
        {"rule": "CD031", "path": "app/cpy/X.cpy", "key": "X"},
    ]
    assert load_baseline(tmp_path / "missing.json") == []


def test_format():
    assert _f().format() == "app/cbl/A.cbl:10: CD020 message"
