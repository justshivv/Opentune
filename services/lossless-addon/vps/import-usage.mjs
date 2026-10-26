// Run with the service STOPPED. Imports a D1 SQL export into a new SQLite database.
import { DatabaseSync } from 'node:sqlite';
import { readFileSync, existsSync, renameSync, unlinkSync } from 'node:fs';
import { join } from 'node:path';
const [source, directory] = process.argv.slice(2);
if (!source || !directory) throw new Error('Usage: node import-usage.mjs export.sql /var/lib/opentune-addon');
const target=join(directory,'usage.sqlite'), temporary=target+'.importing';
if (existsSync(target)) throw new Error('usage.sqlite already exists. Back it up and move it aside with the service stopped; never combine two divergent stores blindly.');
if (existsSync(temporary)) throw new Error('A previous import exists. Inspect it before retrying.');
let db;
try {
 db=new DatabaseSync(temporary);
 db.exec(readFileSync(source,'utf8'));
 db.prepare('SELECT installation,day,version,plays FROM activity LIMIT 1').all();
 if(db.prepare('PRAGMA integrity_check').get().integrity_check!=='ok') throw new Error('Database integrity check failed');
 db.close();db=null;renameSync(temporary,target);
 console.log('Imported usage.sqlite. Set owner opentune:opentune before restarting. The server applies additive schema migrations.');
} catch(error) {db?.close();if(existsSync(temporary))unlinkSync(temporary);throw error;}
