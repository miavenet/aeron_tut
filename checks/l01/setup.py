#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
sys.dont_write_bytecode=True
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools'))
from harness import print_result, Run, java_argv, result, JAR

def main():
    try:
        with Run('wp1',parent=ROOT/'manifest/evidence/wp1') as run:
            def call(name,argv):
                c=run.start(name,argv);run.until(lambda:c.poll() is not None,description=name);rc=run.wait(c);return rc,c.log.read_text()
            javabin=str(Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else 'java'
            javac=str(Path(os.environ['JAVA_HOME'])/'bin/javac') if os.environ.get('JAVA_HOME') else 'javac'
            classes=run.path/'classes';classes.mkdir()
            rc,out=call('compile',[javac,'-cp',str(JAR),'-d',str(classes),str(ROOT/'labs/l01/ops/Primer.java')]);assert rc==0,out
            rc,out=call('missing-open',[javabin,'-cp',str(classes)+os.pathsep+str(JAR),'Primer']);assert rc!=0 and 'IllegalAccessError' in out,out
            rc,out=call('wrapper',java_argv('Primer',classpath=[classes]));assert rc==0 and 'PRIMER PASS byte=7' in out,out
            print(out.strip())
            # Deliberately two words stored in one zsh scalar; never interpolated by Python.
            rc,out=call('zsh-scalar',['zsh','-f','-c','F="--add-opens java.base/jdk.internal.misc=ALL-UNNAMED"; "$1" $F -version','wp1',javabin]);assert rc!=0 and 'Unrecognized option' in out,out
            rc,out=call('bash-array',['bash','-c','F=(--add-opens java.base/jdk.internal.misc=ALL-UNNAMED); "$1" "${F[@]}" -version','wp1',javabin]);assert rc==0,out
            return result(0,[{'id':'setup-negative-positive','pass':True,'detail':'Missing module opening and scalar shell argument fail; wrapper and Bash array pass.'}],artifacts=str(run.path.relative_to(ROOT)))
    except Exception as e:
        return result(2,[{'id':'setup-environment','pass':False,'detail':str(e)}])
if __name__=='__main__':
    r=main();print_result(r);sys.exit(r['exit_code'])
