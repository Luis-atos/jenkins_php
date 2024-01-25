// Fichero que contiene el pipeline común a todos los proyectos
// vars/exec_pipeline.groovy
import groovy.transform.Field
import java.text.SimpleDateFormat

def jobBaseName = ""
def MAJOR = ""
def MINOR = ""
def PATCH = ""

def taskUrl = ""
def unique_release = false
def num_rama_release = ""
def num_rama_release_local = ""
def num_rama_release_version = ""
String[] NomDespliegue = new String[4]; // Tamaño total
String[] RutaDespliegue = new String[4]; // Tamaño total
def CleanWorkspace = ""
String SonarCoverageResponse = "" // Para aviso de cobertura de código en email cuando se pasa a PRE
def proyKey = "" // Se pone aquí para usarlo en verificación de cobertura de código cuando se pasa a PRE
def userID = "" // Se incluye el Usuario que lanza el proceso para validaciones Sonar y logs
def branchAux = ""

def call(Map params) {
    pipeline {
        agent {
            node {
                label 'linux'
            }
        }

        options {
            timeout(time: 1, unit: 'HOURS') // timeout on whole pipeline job
        }

        environment {
            env_desa = 'Desarrollo'
            env_pre = 'Pre-produccion'
            env_pro = 'Produccion'
            env_val = 'Validacion'
            env_hotf = 'Hotfix'
        }

        stages {
            stage('Entrada Version') {
                steps {
                    script {

                        try {
                            userID = currentBuild.getRawBuild().getCauses()[0].getUserId()
                        } catch (e) {
                            userID = "ERROR"
                            println("Error al recuperar el usuario que lanza el JOB")
                        }

                        echo """*************** Parametros de entrada *************************
                        Tecnologia: ${params.Tecnologia}
                        ExisteBD: ${params.ExisteBD}
                        ExisteRPT: ${params.ExisteRPT}
                        LanzarSonar: ${params.LanzarSonar}
                        SubCarpetaFTP: ${params.SubCarpetaFTP}
                        RutaPackage: ${params.RutaPackage}
                        DesaWindows: ${params.DesaWindows}
                        NomDespliegue: ${params.NomDespliegue}
                        RutaDespliegue: ${params.RutaDespliegue}
                        CleanWorkspace: ${params.CleanWorkspace}
                        SmokeTest: ${params.SmokeTest}
                        auxInstall: ${params.auxInstall}
                        Usuario: ${userID}"""

                        branchAux = env.BRANCH_NAME
                        if (env.BRANCH_NAME.matches("HOTFIX(.*)"))
                            branchAux = "HOTFIX"
                        if (env.BRANCH_NAME.matches("RELEASE(.*)"))
                            branchAux = "RELEASE"
                        if (env.BRANCH_NAME.matches("feature(.*)"))
                            branchAux = "feature"

                        echo "branchAux: ${branchAux}"

                        params.Tecnologia = "functions_" + params.Tecnologia;
                        NomDespliegue = params.get("NomDespliegue");
                        RutaDespliegue = params.get("RutaDespliegue");
                        CleanWorkspace = params.get("CleanWorkspace");
                        SmokeTest = params.get("SmokeTest");

                        if (env.BRANCH_NAME.matches("master")) {
                            // Si no hay versión de entrada, indica que la tarea ha sido lanzada automáticamente para indexar la rama.
                            echo "La Rama ha sido Indexada correctamente."
                            currentBuild.result = 'NOT_BUILT'
                            return
                        }

                        env.ENTORNO = ""
                        jobBaseName = "${env.JOB_NAME}".split('/')[0]
                        SonarCoverageResponse = jobBaseName
                        try {
                            timeout(time: 30, unit: 'SECONDS') {

                                if ((!env.BRANCH_NAME.matches("HOTFIX(.*)")) && (!env.BRANCH_NAME.matches("RELEASE(.*)"))) {
                                    def INPUT_PARAMS = input(
                                        id: 'env.VERSION',
                                        message: 'Versión a desplegar',
                                        parameters: [
                                            [$class: 'StringParameterDefinition', defaultValue: '0', description: 'Version Major', name: 'MAJOR'],
                                            [$class: 'StringParameterDefinition', defaultValue: '0', description: 'Version Minor', name: 'MINOR'],
                                            [$class: 'StringParameterDefinition', defaultValue: '0', description: 'Version parcheo de bugfix', name: 'PATCH']
                                        ]);

                                    MAJOR = INPUT_PARAMS.MAJOR;
                                    MINOR = INPUT_PARAMS.MINOR;
                                    PATCH = INPUT_PARAMS.PATCH;
                                } else {

                                    VERSION_RAMA = "${env.BRANCH_NAME}".split('-')[1].tokenize(".")
                                    MAJOR = VERSION_RAMA[0].toString()
                                    MINOR = VERSION_RAMA[1].toString()
                                    PATCH = VERSION_RAMA[2].toString()
                                }

                            }
                        } catch (err) { // timeout reached or input false
                            user = err.getCauses()[0].getUser().toString()
                            if (user.toString() == 'SYSTEM') { // Si el usuario es SYSTEM, es un timeout
                                echo "Job abortado por TIMEOUT en la selección de versión"
                            } else { // if not and input is false it's the user
                                echo "Job cancelado por usuario: [${user}]"
                            }
                            currentBuild.result = 'ABORTED'
                            throw new hudson.AbortException('')
                        }

                        echo("Versión: " + MAJOR + "." + MINOR + "." + PATCH)
                    }
                }
            }
            stage('Número Versión') {
                steps {
                    script {
                        if ((env.BRANCH_NAME.matches("HOTFIX(.*)")) || (env.BRANCH_NAME.matches("RELEASE(.*)")) || (env.BRANCH_NAME.matches("develop")) || (env.BRANCH_NAME.matches("feature(.*)"))) {
                            try {
                                if (MAJOR && ("0.0.0" != VersionNumber([versionNumberString: "${MAJOR}.${MINOR}.${PATCH}"]))) {
                                    VERSION_CONTROL = MAJOR + "." + MINOR + "." + PATCH;
                                    VERSION_INPUT = VersionNumber([versionNumberString: "${MAJOR}.${MINOR}.${PATCH}"])
                                    VERSION = VERSION_CONTROL + '.0';
                                    echo "Version obtenida por input: ${VERSION}"
                                } else {
                                    // Si no recoge los valores en el formulario de inicio lanza la excepción.
                                    throw err
                                }
                            } catch (err) {
                                // Si no hay versión de entrada, indica que la tarea ha sido lanzada automáticamente para indexar la rama.
                                echo "La Rama ha sido Indexada correctamente."
                                env.BRANCH_NAME = 'master'; // Evitamos que entre en ninguna otra tarea.
                                currentBuild.result = 'NOT_BUILT'
                                return
                            }

                            // Comprobamos si existe una rama Release creada, y si existe, es la misma versión que la actual
                            echo "before:unique_rama_release:${VERSION}" //,${num_rama_release},${num_rama_release_version}" 

                            sshagent(['jenkins-generated-ssh-key']) {
                                sh "git fetch -p origin"
                            }

                            def release_version_line = "(git branch -a | grep -i origin/RELEASE-" + VERSION + " | wc -l)"

                            // número total ramas de release
                            num_rama_release = sh(script: "(git branch -a | grep -i origin/release | wc -l)", returnStdout: true).trim()
                            // número de ramas de versión actual
                            num_rama_release_version = sh(script: release_version_line, returnStdout: true).trim()

                            unique_release = (num_rama_release == '0' || ((num_rama_release_version == '1') && (num_rama_release == '1'))).toBoolean()

                            echo "num_rama_release: ${num_rama_release}; num_rama_release_version: ${num_rama_release_version}; unique_release = ${unique_release}"

                            if (env.BRANCH_NAME == 'develop' || (env.BRANCH_NAME.matches("feature(.*)"))) { // Si ejecutamos desde la rama Develop
                                // Si ya existen Ramas en Release, recogemos su versión
                                if (!unique_release) {
                                    VERSION_REMOTA = functions_commons.remote_branch_version().tokenize(".")
                                    MAJOR = VERSION_REMOTA[0].toString()
                                    MINOR = VERSION_REMOTA[1].toString()
                                    PATCH = VERSION_REMOTA[2].toString()
                                    VERSION_CONTROL = VersionNumber([versionNumberString: "${MAJOR}.${MINOR}.${PATCH}"])

                                }

                                echo 'Rama DEVELOP. Versión de proyecto: ' + VERSION
                                env.ENTORNO = "${env_desa}"

                            } else { // Si ejecutamos desde la rama Release o Hotfix

                                if (env.BRANCH_NAME.matches("HOTFIX(.*)")) {

                                    echo 'Rama HOTFIX. Versión de proyecto: ' + VERSION
                                    env.ENTORNO = "${env_hotf}"
                                }

                                if (env.BRANCH_NAME.matches("RELEASE(.*)")) {

                                    // Recogemos la versión del proyecto a partir del nombre de la rama
                                    VERSION_RAMA_ACTUAL = "${env.BRANCH_NAME}".split('-')[1].tokenize(".")
                                    MAJOR = VERSION_RAMA_ACTUAL[0].toString()
                                    MINOR = VERSION_RAMA_ACTUAL[1].toString()
                                    PATCH = VERSION_RAMA_ACTUAL[2].toString()
                                    BUILD = VERSION_RAMA_ACTUAL[3].toInteger() + 1
                                    VERSION_CONTROL = VersionNumber([versionNumberString: "${MAJOR}.${MINOR}.${PATCH}"])
                                    VERSION = VersionNumber([versionNumberString: "${MAJOR}.${MINOR}.${PATCH}.${BUILD}"])

                                    echo 'Rama RELEASE. Versión de proyecto: ' + VERSION
                                    env.ENTORNO = "${env_pro}"
                                }
                            }

                        } else {
                            echo "Esta rama no debe ejecutarse en Jenkins"
                            currentBuild.result = 'NOT_BUILT'
                            return;
                        }
                    }
                }
            }

            stage('Elección Entorno') {
                when {
                    expression {
                        ((env.BRANCH_NAME.matches("HOTFIX(.*)")) ||
                            (env.BRANCH_NAME.matches("RELEASE(.*)")) ||
                            (env.BRANCH_NAME.matches("develop")) ||
                            (env.BRANCH_NAME.matches("feature(.*)"))
                        )
                    }
                }
                steps {
                    sh '''
                    mkdir -p bin
                    mkdir -p conf
                    mkdir -p doc
                    '''
                    script {
                        if (params.ExisteBD != "") {
                            sh '''mkdir -p bd'''
                        }
                        if (params.ExisteRPT != "0") {
                            sh '''mkdir - p rpt'''
                        }

                        VERSION_POM = VERSION;
                        try {
                            timeout(time: 30, unit: 'SECONDS') {
                                if (env.ENTORNO == 'Desarrollo') {
                                    try {
                                        def entornos = "${env_desa}\n${env_pre}"
                                        /*if ((env.BRANCH_NAME.matches("feature(.*)"))) {
                                            entornos = "${env_desa}\n${env_pre}"
                                        }*/
                                        if ((env.BRANCH_NAME.matches("RELEASE-(.*)"))) {
                                            entornos = "${env_desa}\n${env_pro}"
                                        }
                                        // Se muestra un formulario para elegir en que entorno se quiere desplegar
                                        env.ENTORNO = input(message: 'Interacción Usuario requerido',
                                            ok: 'Seleccionar',
                                            parameters: [choice(name: 'Elección Entorno', choices: "${entornos}", description: '¿Sobre qué entorno deseas desplegar?')])

                                    } catch (err) { // Si Cancelamos el formulario.	
                                        user = err.getCauses()[0].getUser().toString()
                                        if (user.toString() == 'SYSTEM') { // Si el usuario es SYSTEM, es un timeout
                                            echo "Job abortado por TIMEOUT en la selección del entorno"
                                        } else { // if not and input is false it's the user
                                            echo "Job cancelado por usuario: [${user}]"
                                        }
                                        currentBuild.result = 'ABORTED'
                                        throw new hudson.AbortException('')
                                    }

                                } else if (env.ENTORNO == 'Produccion') {
                                    try {
                                        // Se muestra un formulario para elegir en que entorno se quiere desplegar
                                        env.ENTORNO = input(message: 'Interacción Usuario requerido',
                                            ok: 'Seleccionar',
                                            parameters: [choice(name: 'Elección Entorno', choices: "${env_val}\n${env_pre}\n${env_pro}", description: '¿Desea desplegar en VALIDACIÓN, actualizar PREPRODUCCIÓN o integrar en PRODUCCIÓN?')])

                                    } catch (err) { // Si Cancelamos el formulario.
                                        user = err.getCauses()[0].getUser().toString()
                                        if (user.toString() == 'SYSTEM') { // Si el usuario es SYSTEM, es un timeout
                                            echo "Job abortado por TIMEOUT en la selección del entorno"
                                        } else { // if not and input is false it's the user
                                            echo "Job cancelado por usuario: [${user}]"
                                        }
                                        currentBuild.result = 'ABORTED'
                                        throw new hudson.AbortException('')
                                    }

                                }
                            }
                        } catch (err) { // timeout reached or input false
                            user = err.getCauses()[0].getUser().toString()
                            if (user.toString() == 'SYSTEM') { // Si el usuario es SYSTEM, es un timeout
                                echo "Job abortado por TIMEOUT en la selección del entorno"
                            } else { // if not and input is false it's the user
                                echo "Job cancelado por usuario: [${user}]"
                            }
                            currentBuild.result = 'ABORTED'
                            throw new hudson.AbortException('')
                        }

                        // Si vamos a realizar tareas sobre la Rama Master, tenemos que asegurarnos que se han realizado las pruebas correspondientes.
                        if (env.ENTORNO == 'Produccion' || env.ENTORNO == 'Hotfix') {
                            input(id: 'DesplegadoProduccion', message: '¿Está seguro que no existen errores en PRODUCCIÓN?\n\n Si continua, se hará merge con Rama Master y se eliminará la Rama ' + env.BRANCH_NAME + '.')
                        }

                        if (env.ENTORNO == 'Produccion') {
                            // Si la rama que queremos subir no es la RELEASE_1.1.1 da error
                            VERSION_POM = "${env.BRANCH_NAME}".split('-')[1]
                        }

                        sshagent(['jenkins-generated-ssh-key']) {

                            if (env.ENTORNO != 'Desarrollo') {
                                def VERSIONA = VERSION_POM.tokenize(".")
                                // npm usa fixer.js valida que la versión sea formato 0.0.0 por lo que no se puede usar el formato 0.0.0.0 porque se interrumpirá con error.
                                VERSIONA = VersionNumber([versionNumberString: "${VERSIONA[0].toString()}.${VERSIONA[1].toString()}.${VERSIONA[2].toString()}"])

                                // Modificamos la versión del proyecto (No necesario en PHP)
                                //sh "git add ."
                                //sh "git diff-index --quiet ${BRANCH_NAME} || git commit -m 'Actualizamos Version Package.json'"
                                //sh "git push -f origin ${BRANCH_NAME}"
                            }

                            def proyKey_str = ""
                            try {
                                def output = sh(script: "grep 'sonar.projectKey' source${params.RutaPackage}/sonar-project.properties | cut -d'=' -f2", returnStdout: true)
                                echo "Proyecto Sonar: ${output}"
                                proyKey = output.trim()
                            } catch (err) {
                                echo "no obtenido proyecto Sonar " + err
                            }
                            if (proyKey == "") {
                                echo "no existe sonar.projectKey. Se sustituye por JobBaseName"
                                proyKey = jobBaseName
                            }
                        }

                        print env.ENTORNO;

                    }
                }
            }

            stage('Build & SonarQube Test') {
                when {
                    expression {
                        (
                            ((env.BRANCH_NAME.matches("HOTFIX(.*)")) ||
                                (env.BRANCH_NAME.matches("RELEASE(.*)")) ||
                                (env.BRANCH_NAME.matches("feature(.*)")) ||
                                (env.BRANCH_NAME.matches("develop"))) &&
                            (!env.ENTORNO.matches("") && !env.ENTORNO.matches("Produccion"))
                        )
                    }
                }
                steps {
                    script {
                        print env.ENTORNO;
                        //if (env.BRANCH_NAME.matches("feature(.*)"))
                        //    params.LanzarSonar = "0"

                        // Consulta excepciones de Lanzar Sonar para el proyecto:
                        def l_fileVar = "/var/lib/jenkins/Jenkins_core_pipeline";

                        //if (env.BRANCH_NAME=="develop")
                        //   proyKey = proyKey + "-develop"

                        res = functions_commons.getVarFile(l_fileVar, proyKey, branchAux, env.ENTORNO, userID, "LanzarSonar", "Integer");
                        println("Llamada recuperar excepción Lanzar Sonar para proyecto: ${proyKey}, rama: ${branchAux}, entorno: ${env.ENTORNO}, Usuario: ${userID}. LanzarSonar antes: ${params.LanzarSonar} Recupera: ${res}");
                        // ************* Cuando se ponga en marcha quitar el comentario de la siguiente línea 
                        params.LanzarSonar = res.toString()

                        //this."${params.Tecnologia}".install_binary_path(params.auxInstall,params.RutaPackage);
                        //taskUrl = this."${params.Tecnologia}".build_and_sonarqube_task(jobBaseName, params.LanzarSonar, params.RutaPackage, env.ENTORNO,env.BRANCH_NAME,proyKey, params.NomDespliegue[0],params.NomDespliegue[1]);
                    }
                    withSonarQubeEnv('Sonar DIVINDES') {
                        script {
                            try {
                                if (params.LanzarSonar == "1") {
                                    echo "Build Con SONAR:"
                                    def output = sh(script: "sonar-scanner -Dsonar.projectKey=php:experimento_php -Dsonar.projectName=experimento_php -Dsonar.login=Developer -Dsonar.password=Developer -Dsonar.tests='' -Dsonar.sources=.", returnStdout: true)
                                    taskUrl = output.find(~"https://sonar.servdev.mdef.es/api/ce/task\\?id=[\\w-]*")
                                }
                            } catch (err) {
                                echo "Error al lanzar sonar-scanner " + err
                            }
                        }
                    }
                }
            }

            stage('Check Quality Gate') {
                when {
                    expression {
                        // Pasar el test SONAR siempre que no vayamos a ejecutar las acciones de PRODUCCIÓN
                        ((env.BRANCH_NAME.matches("develop") ||
                            (env.ENTORNO == 'Pre-produccion') ||
                            (env.BRANCH_NAME.matches("HOTFIX(.*)"))) && env.ENTORNO != '' && params.LanzarSonar == "1")
                    }
                }
                steps {
                    script {
                        def exit = false
                        timeout(time:(50), unit: 'MINUTES') { 
    						while(!exit){
    						    sleep 10
    						    def sonarData = readJSON text: sh(script: "curl -u Developer:Developer " + taskUrl, returnStdout: true)
    						    echo sonarData.toString()
    							try {
    								if(sonarData.task.status == "SUCCESS") {
    								   exit = true
    								   echo "Sonar: Análisis Exitoso"
    								   def qualityGateData = readJSON text: sh(script: "curl -u Developer:Developer https://sonar.servdev.mdef.es/api/qualitygates/project_status?projectKey=" + sonarData.task.componentKey, returnStdout: true)
    								   echo qualityGateData.toString()
    								   if(qualityGateData.projectStatus.status != "OK"){
    								       exit = true
    								       error "SONAR: QUALITY GATES NOT OK"
    								       currentBuild.result = "FAILURE"
    								   } else {
    								       echo "Sonar: Quality Gates OK"
    								   }
    						   		} else if(sonarData.task.status == "CANCELED") {
    									exit = true					   		                                   
    						   		    error "SONAR: Despliegue rechazado debido a baja calidad del software: ${qg.status}"
    				            		currentBuild.result = 'ABORTED'
    						   		} else if(sonarData.task.status == "FAILED"){
    						   			exit = true
    						   			error "SONAR: Despliegue rechazado debido a baja calidad del software: ${qg.status}"
    						   			currentBuild.result = "FAILURE"
    				            	} else {
    				            		echo "Seguimos pendiente de análisis: " + sonarData.task.status
    				            	}
    							}catch(err) {
    						   	      error "SONAR: Hay un error al realizar la llamada a Sonar: "+ err
    						   	      exit = true;
    							}
    						}
    					}

                        /*
    				    println ("Validación incremento cobertura de código pase a PRE: ${proyKey} , ${userID}")
    					SonarCoverageResponse="Sin comprobar"
    					def swResp = false;
    					(swResp , SonarCoverageResponse) = functions_commons.check_sonar_history(proyKey,userID)
    					if (swResp){
    					    println ("SONAR: Validación cobertura de código OK: ${SonarCoverageResponse}")
    					}else{
    					    println ("**** SONAR: Despliegue rechazado. No mejora cobertura de código: ${SonarCoverageResponse}")
    					    //if (env.ENTORNO==env_pre){
    					    //    error "SONAR: Despliegue rechazado. No mejora cobertura de código: ${SonarCoverageResponse}"
    						//    currentBuild.result = "FAILURE"    
    					    //}
    					}
    					*/

                    }
                }
            }

            stage('Entornos') {

                parallel {
                    stage('1- DES') {
                        when {
                            environment name: 'ENTORNO', value: 'Desarrollo'
                        }
                        stages {
                            stage('Deploy Develop') {
                                steps {
                                    script {
                                        def desplegar = new Deploy()
                                        desplegar.deploy_develop_ftp(this)
                                    }
                                }
                            }
                        }
                    }

                    stage('2- VAL') {
                        when {
                            environment name: 'ENTORNO', value: 'Validacion'
                        }
                        stages {
                            stage('Deploy validation') {
                                steps {
                                    script {
                                        def desplegar = new Deploy()
                                        desplegar.deploy_validation_ftp(this)
                                    }
                                }
                            }
                            stage('Smoke Test') {
                                when {
                                    expression {
                                        (params.SmokeTest == '1')
                                    }
                                }
                                steps {
                                    script {
                                        try {
                                            sleep 30 // para que arranque correctamente apache
                                            // Esto es un apaño porque los JS que descarga contienen errores.
                                            sh "pwd"
                                            // Si no existe la carpeta testlink-api-client, lo instala.
                                            //sh 'test -d "source/node_modules/testlink-api-client/lib/" && echo "Existe testlink-api-client" || echo "Instalar Testlink-api-client" npm install --prefix source testlink-api-client'
                                            sh "npm install --prefix source testlink-api-client"
                                            sh "cp -r /usr/lib/node_modules/testlink-api-client/lib/* source/node_modules/testlink-api-client/lib/"
                                            sh "protractor source/e2e/conf.SmokeTest.js --suite=SmokeTest --params.entorno=Validacion"
                                        } catch (err) { // timeout reached or input false
                                            echo "Ha ocurrido un error al  realizar las pruebas funcionales Smoke Test" + err
                                        }
                                    }
                                }
                            }
                        }
                    }

                    stage('3- PRE') {
                        when {
                            environment name: 'ENTORNO', value: 'Pre-produccion'
                        }
                        stages {
                            stage('Create/Update Release Branch') {
                                steps {
                                    script {
                                        sshagent(['jenkins-generated-ssh-key']) {

                                            if (unique_release || functions_commons.is_same_version(VERSION_INPUT, VERSION_CONTROL)) {

                                                sh "git fetch -p"
                                                sh "git branch -a"

                                                if (env.BRANCH_NAME.matches("develop")) {
                                                    // Si estamos en la rama Develop

                                                    if (num_rama_release == '1') {
                                                        // Si ya hay una rama release previamente, localizamos su id y la borramos
                                                        VERSION_AUX = functions_commons.remote_branch_version();

                                                        // Si la rama 'release-xxx' ya existe
                                                        echo "Borra la Release previa"
                                                        sh "git push origin --delete RELEASE-${VERSION_AUX}"

                                                    }

                                                    //if(num_rama_release_local != '0'){
                                                    if (num_rama_release_version != '0') {
                                                        sh "git branch -d RELEASE-${VERSION}"
                                                    }

                                                    // Creamos una rama 'release-xxx' a partir de la rama 'develop'
                                                    sh "git checkout -b RELEASE-${VERSION} ${BRANCH_NAME}"

                                                    // Se hace PUSH de la rama 'release-xxx'en el repositorio remoto y la hace disponible para todos.
                                                    sh "git push -f origin RELEASE-${VERSION}"

                                                } else {
                                                    // Si estamos en la rama RELEASE

                                                    // Creamos una rama 'release-xxx +1' a partir de la rama 'release-xxx'
                                                    sh "git checkout -b RELEASE-${VERSION} ${BRANCH_NAME}"
                                                    sh "git push -f origin RELEASE-${VERSION}"
                                                    sh "git branch -a"
                                                    if (num_rama_release == '1') {
                                                        // Si ya existe una release, tenemos que aumentar de versión, y para ello tenemos que borrar la versión actual
                                                        sh "git push origin --delete ${BRANCH_NAME}"
                                                        sh "git branch -d ${BRANCH_NAME}"
                                                        sh "git branch -a"
                                                    }

                                                    sh "git add -A"
                                                    sh "git diff-index --quiet ${BRANCH_NAME} || git commit -m 'cambios en comun'"

                                                    // hacemos merge de los cambios encontrados en 'release-xxx' hacia la rama 'develop'
                                                    sh "git checkout develop"
                                                    sh "git add ."
                                                    sh "git diff-index --quiet develop || git commit -m 'Actualizamos Develop'"

                                                    // hacemos merge de la rama "release-xxx" en la rama Master. En los posibles conflictos nos quedamos con los datos de la versión "release-xxx"
                                                    sh "git merge --strategy-option theirs RELEASE-${VERSION} -m 'JENKINS Release version ${VERSION}'"

                                                    sh "git push -f origin develop"
                                                    sh "git branch -a"
                                                }

                                            } else {
                                                error 'Ya Existe una Rama Release. No puede haber varias Release simultáneas con diferente número de versión'
                                            }
                                        }
                                    }

                                }
                            }
                            stage('Build ZIP') {
                                steps {
                                    script {
                                        String zipName = jobBaseName + "_v" + VERSION + ".zip"
                                        this."${params.Tecnologia}".generate_zip(zipName)
                                    }
                                }
                            }

                            stage('Upload Artifacts') {
                                steps {
                                    script {
                                        // Se copia en la maquina destino
                                        String zipName = jobBaseName + "_v" + VERSION + ".zip"

                                        def FTP_PASES = [:]
                                            FTP_PASES.name = "FTP Pases"
                                            FTP_PASES.host = "ftpaplicaciones.mdef.es"
                                            FTP_PASES.user = "${env.FTP_USER}"
                                            FTP_PASES.password = "${env.FTP_PWD}"
                                            FTP_PASES.allowAnyHosts = true
                                            FTP_PASES_RUTA = "${env.FTP_RUTA}${params.SubCarpetaFTP}/PRE" 

                                        sshPut(remote: FTP_PASES, from: "../${zipName}", into: "${FTP_PASES_RUTA}")
                                        sshagent(['jenkins-generated-ssh-key']) {
                                            sh """rm ../${zipName}"""
                                        }
                                    }
                                }
                            }

                        }
                    }

                    stage('4- PRO') {
                        when {
                            environment name: 'ENTORNO', value: 'Produccion'
                        }

                        stages {
                            stage('Update Release Branch') {
                                steps {
                                    script {
                                        sshagent(['jenkins-generated-ssh-key']) {

                                            // Actualmente en la rama 'release-xxx'
                                            // descarga solo el contenido nuevo del repositorio remoto y hacemos PUSH
                                            VERSION_RAMA_ACTUAL = "${env.BRANCH_NAME}".split('-')[1]

                                            // Actualizamos los posibles cambios de Release.											
                                            sh "git add ."
                                            sh "git diff-index --quiet ${BRANCH_NAME} || git commit -m 'Actualizamos Release'"
                                            sh "git push -f origin ${BRANCH_NAME}"
                                        }
                                    }
                                }
                            }

                            stage('Build ZIP') {
                                steps {
                                    script {
                                        String zipName = jobBaseName + "_v" + VERSION + ".zip"
                                        this."${params.Tecnologia}".generate_zip(zipName)
                                    }
                                }
                            }

                            stage('Merge Master Branch') {
                                steps {
                                    script {
                                        sshagent(['jenkins-generated-ssh-key']) {

                                            // Cambiamos a la rama master
                                            sh "git fetch -p"
                                            sh "git checkout master"
                                            sh "git pull"

                                            sh "git diff-index --quiet master || git commit -a -m 'Alinea Master'"

                                            // hacemos merge de la rama "release-xxx" en la rama Master. En los posibles conflictos nos quedamos con los datos de la versión "release-xxx"
                                            sh "git merge --strategy-option theirs RELEASE-${VERSION_RAMA_ACTUAL} -m 'JENKINS version ${VERSION_RAMA_ACTUAL}'"

                                            // Se hace PUSH de los cambios encontrados, actualizando el repositorio remoto.
                                            // 'origin' hace la rama 'develop' disponible para todos
                                            sh "git push origin master"
                                        }
                                    }
                                }
                            }

                            stage('Upload Artifacts') {
                                steps {
                                    script {
                                        // Se copia en la maquina destino
                                        String zipName = jobBaseName + "_v" + VERSION + ".zip"

                                        def FTP_PASES = [:]
                                            FTP_PASES.name = "FTP Pases"
                                            FTP_PASES.host = "ftpaplicaciones.mdef.es"
                                            FTP_PASES.user = "${env.FTP_USER}"
                                            FTP_PASES.password = "${env.FTP_PWD}"
                                            FTP_PASES.allowAnyHosts = true
                                            FTP_PASES_RUTA = "${env.FTP_RUTA}${params.SubCarpetaFTP}/PRO" 

                                        sshPut(remote: FTP_PASES, from: "../${zipName}", into: "${FTP_PASES_RUTA}")
                                        sshagent(['jenkins-generated-ssh-key']) {
                                            sh """rm ../${zipName}"""
                                        }
                                    }
                                }
                            }
                            stage('Create Tag') {
                                steps {
                                    script {
                                        sshagent(['jenkins-generated-ssh-key']) {

                                            FECHA_TAG = sh(script: 'date +"%Y%m%d%H%M%S"', returnStdout: true).trim()
                                            // Creamos un tag a partir de la versión actualizada de MASTER y hacemos push
                                            sh "git tag -fa MASTER-${VERSION_RAMA_ACTUAL}-${FECHA_TAG} -m 'JENKINS version of Master v${VERSION_RAMA_ACTUAL}'"
                                            sh "git push --tags"
                                        }
                                    }
                                }
                            }
                            stage('Merge Develop Branch') {
                                steps {
                                    script {
                                        sshagent(['jenkins-generated-ssh-key']) {
                                            // hacemos merge de los cambios encontrados en 'release-xxx' hacia la rama 'develop'
                                            sh "git checkout develop"

                                            sh "git add ."
                                            sh "git diff-index --quiet develop || git commit -m 'Alineamos develop'"

                                            sh "git merge RELEASE-${VERSION_RAMA_ACTUAL}"
                                            sh "git push -f origin develop"
                                        }
                                    }
                                }
                            }
                            stage('Delete Release Branch') {
                                steps {
                                    script {
                                        sshagent(['jenkins-generated-ssh-key']) {

                                            //Eliminamos la rama local y remota de 'release-xxx'
                                            sh "git push --delete origin RELEASE-${VERSION_RAMA_ACTUAL}"
                                            sh "git branch -d RELEASE-${VERSION_RAMA_ACTUAL}"
                                        }
                                    }
                                }
                            }
                        }
                    }

                    stage('5- HTF') {
                        when {
                            environment name: 'ENTORNO', value: 'Hotfix'
                        }
                        stages {
                            stage('Merge Develop Branch') {
                                steps {
                                    script {
                                        // Enviamos un email para avisar de un cambio tipo Hotfix
                                        functions_commons.sendEmail_hotfix()

                                        sshagent(['jenkins-generated-ssh-key']) {

                                            VERSION_RAMA_ACTUAL = "${env.BRANCH_NAME}".split('-')[1]

                                            // hacemos merge de los cambios encontrados en 'hotfix-xxx' hacia la rama 'develop'
                                            sh "git checkout -f develop"
                                            sh "git diff-index --quiet develop || git commit -a -m 'Alinea Develop'"

                                            // hacemos merge de la rama "hotfix-xxx" en la rama Master. En los posibles conflictos nos quedamos con los datos de la versión "hotfix-xxx"
                                            sh "git merge --strategy-option theirs HOTFIX-${VERSION_RAMA_ACTUAL} -m 'JENKINS HotFix version ${VERSION}'"

                                            sh "git push -f origin develop"
                                        }
                                    }
                                }
                            }
                            stage('Merge Release Branch') {
                                when {
                                    allOf {
                                        expression {
                                            (env.BRANCH_NAME.matches("HOTFIX(.*)"));
                                            num_rama_release == '1'
                                        }
                                    }
                                }
                                steps {
                                    script {
                                        sshagent(['jenkins-generated-ssh-key']) {

                                            echo "La Release anterior existe. Se reemplaza por la versión de la Rama Hotfix"
                                            VERSION_AUX = functions_commons.remote_branch_version();

                                            // hacemos merge de los cambios encontrados en 'hotfix-xxx' hacia la rama 'release-xxx'
                                            sh "git checkout RELEASE-${VERSION_AUX}"
                                            sh "git diff-index --quiet develop || git commit -a -m 'Alinea Develop'"

                                            // hacemos merge de la rama "hotfix-xxx" en la rama Master. En los posibles conflictos nos quedamos con los datos de la versión "hotfix-xxx"
                                            sh "git merge --strategy-option theirs HOTFIX-${VERSION_RAMA_ACTUAL} -m 'JENKINS HotFix version ${VERSION}'"

                                            sh "git push -f origin RELEASE-${VERSION_AUX}"

                                            if (functions_commons.compareVersion(VERSION_AUX, VERSION)) {

                                                def VERSIONA = VERSION_POM.tokenize(".")
                                                VERSIONA = VersionNumber([versionNumberString: "${VERSIONA[0].toString()}.${VERSIONA[1].toString()}.${VERSIONA[2].toString()}"])

                                                // Modificamos la versión del proyecto. Se comenta para PHP
                                                //def versiona_str = "grep 'version' source/package.json | cut -d':' -f2 | cut -d',' -f1 | xargs -I OutPutFromGrep sed -i 's/version\": \"OutPutFromGrep/version\": \"${VERSIONA}/g' source/package.json"
                                                //sh (script: versiona_str, returnStdout: true).trim();
                                                //sh "git add ."
                                                //sh "git diff-index --quiet RELEASE-${VERSION_AUX} || git commit -m 'Actualizamos Version Package.json'"
                                                //sh "git push -f origin RELEASE-${VERSION_AUX}"
                                            }

                                        }
                                    }
                                }
                            }
                            stage('FTP Upload') {
                                steps {
                                    script {
                                        functions_commons.upload_ftp_task(jobBaseName, NomDespliegue, RutaDespliegue, VERSION, params.ExisteBD, params.ExisteRPT, params.SubCarpetaFTP);
                                    }
                                }
                            }
                            stage('Merge Master Branch') {
                                steps {
                                    script {
                                        sshagent(['jenkins-generated-ssh-key']) {

                                            // Actualmente en la rama 'hotfix-xxx'
                                            // descarga solo el contenido nuevo del repositorio remoto y hacemos PUSH
                                            sh "git fetch -p"

                                            // Cambiamos a la rama master
                                            sh "git checkout master"
                                            sh "git pull"
                                            sh "git diff-index --quiet master || git commit -a -m 'Alinea Master'"

                                            // hacemos merge de la rama "hotfix-xxx" en la rama Master. En los posibles conflictos nos quedamos con los datos de la versión "hotfix-xxx"
                                            sh "git merge --strategy-option theirs HOTFIX-${VERSION_RAMA_ACTUAL} -m 'JENKINS version ${VERSION}'"

                                            // Se hace PUSH de los cambios encontrados, actualizando el repositorio remoto.
                                            // 'origin' hace la rama 'develop' disponible para todos
                                            sh "git push origin master"
                                        }
                                    }
                                }
                            }
                            stage('Create Tag') {
                                steps {
                                    script {
                                        sshagent(['jenkins-generated-ssh-key']) {

                                            FECHA_TAG = sh(script: 'date +"%Y%m%d%H%M%S"', returnStdout: true).trim()
                                            // Creamos un tag a partir de la versión actualizada de MASTER y hacemos push
                                            sh "git tag -fa MASTER-${VERSION}-${FECHA_TAG} -m 'JENKINS version of Master v${VERSION}'"
                                            sh "git push --tags"
                                        }
                                    }
                                }
                            }
                            stage('Delete Hotfix Branch') {
                                steps {
                                    script {
                                        sshagent(['jenkins-generated-ssh-key']) {

                                            //Eliminamos la rama local y remota de 'hotfix-xxx'
                                            sh "git push --delete origin HOTFIX-${VERSION_RAMA_ACTUAL}"
                                            sh "git branch -d HOTFIX-${VERSION_RAMA_ACTUAL}"

                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            stage('Clean Workspace') {
                when {
                    expression {
                        (params.CleanWorkspace == '1')
                    }
                }
                steps {
                    script {
                        echo "Limpiar Workspace (no lo hace realmente si aparece este comentario)"
                        //cleanWs()
                    }
                }
            }
        }
    }
}

return this
