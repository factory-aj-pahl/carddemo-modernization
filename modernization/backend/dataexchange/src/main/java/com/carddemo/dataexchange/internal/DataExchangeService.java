package com.carddemo.dataexchange.internal;

import com.carddemo.common.batch.JobLog;
import com.carddemo.dataexchange.api.AccountExtractFiles;
import com.carddemo.dataexchange.api.DataExchangeJobs;
import com.carddemo.dataexchange.api.ImportFiles;
import com.carddemo.dataexchange.api.JobResult;
import com.carddemo.migration.Layouts;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** The BC-9 jobs over the database tables, with files created mode 600. */
@Service
class DataExchangeService implements DataExchangeJobs {

    private final Tables tables;
    private final JobLog log;
    private final Sinks sinks = new FileSinks();
    private final Clock clock;

    DataExchangeService(JdbcTemplate jdbc, JobLog log, Clock clock) {
        this.tables = new JdbcTables(jdbc);
        this.log = log;
        this.clock = clock;
    }

    @Override
    public JobResult listCards() {
        return new ListingJob(log).run(ListingJob.CARDS, tables.of(Layouts.CARDDATA));
    }

    @Override
    public JobResult listXrefs() {
        return new ListingJob(log).run(ListingJob.XREFS, tables.of(Layouts.CARDXREF));
    }

    @Override
    public JobResult listCustomers() {
        return new ListingJob(log).run(ListingJob.CUSTOMERS, tables.of(Layouts.CUSTDATA));
    }

    @Override
    public JobResult extractAccounts(AccountExtractFiles files) {
        return new AccountExtractJob(log, sinks).run(tables.of(Layouts.ACCTDATA), files);
    }

    @Override
    public JobResult exportAll(Path exportFile) {
        return new ExportJob(log, sinks, clock).run(tables::of, exportFile);
    }

    @Override
    public JobResult importAll(Path exportFile, ImportFiles files) {
        return new ImportJob(log, sinks, clock).run(exportFile, files);
    }
}
