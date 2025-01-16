# Voting App

This project is a web service that allows users to cast votes and stores the results in a database. It is designed to be deployed on Kubernetes using Helm.

## Project Structure

```
voting-app
├── helm-chart
│   ├── Chart.yaml
│   ├── values.yaml
│   ├── templates
│   │   ├── deployment.yaml
│   │   ├── service.yaml
│   │   ├── configmap.yaml
│   │   ├── secret.yaml
│   │   ├── database-deployment.yaml
│   │   ├── database-service.yaml
│   │   └── database-pvc.yaml
├── src
│   ├── app.py
│   ├── requirements.txt
│   ├── templates
│   │   ├── index.html
│   │   └── results.html
│   └── Dockerfile
└── README.md
```

## Setup Instructions

1. **Clone the repository:**
   ```bash
   git clone <repository-url>
   cd voting-app
   ```

2. **Install dependencies:**
   Navigate to the `src` directory and install the required Python packages:
   ```bash
   pip install -r requirements.txt
   ```

3. **Build the Docker image:**
   From the `src` directory, build the Docker image:
   ```bash
   docker build -t voting-app .
   ```

4. **Deploy using Helm:**
   Navigate to the `helm-chart` directory and deploy the application:
   ```bash
   helm install voting-app .
   ```

## Usage

- Access the voting page at `http://<service-ip>:<service-port>/`.
- Users can submit their votes and view the results on the results page.

## License

This project is licensed under the MIT License.