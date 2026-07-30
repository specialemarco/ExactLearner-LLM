from plot_config import PRETTY_MODEL_NAMES
from figures import PATH as FIGURES_PATH
from cache import PATH as CACHE_PATH
import pandas as pd
import matplotlib.pyplot as plt
import numpy as np


def generate_pie_charts():
    data = {}
    for file in CACHE_PATH.glob("*metrics.csv"):
        df = pd.read_csv(file, sep=",", header=0, index_col=False)
        file_name = file.stem
        second_half = file_name.split(".owl")[1]
        model = second_half.split("_")[1]
        df = df.iloc[-1, :-1]
        if model not in data:
            data[model] = pd.DataFrame([df.values], columns=df.index)
        else:
            data[model] = pd.concat([data[model], pd.DataFrame([df.values], columns=df.index)], axis=0)
        if "Unsaturation" in data[model].columns:
            data[model]["Desaturation"] = data[model]["Desaturation"].fillna(0) + data[model]["Unsaturation"].fillna(0)
            data[model].drop(columns=["Unsaturation"], inplace=True)

    # ... tutto uguale fino a:
    # Per la figura finale combinata
    llm_models = []
    llm_values = []
    llm_labels = []
    synthetic_model = None
    synthetic_values = None
    synthetic_labels = None

    for model, df in data.items():
        values = df.sum()
        labels = df.columns
        sizes = values.values

        if np.all(sizes == 0):
            continue

        total = sum(sizes)
        percentages = 100 * sizes / total if total > 0 else np.zeros_like(sizes)

        threshold = 2

        fig, ax = plt.subplots(figsize=(3, 3))
        wedges, texts = ax.pie(
            sizes,
            labels=None,
            autopct=None,
            startangle=140,
            textprops=dict(color="black")
        )

        for wedge, pct in zip(wedges, percentages):
            if pct > threshold:
                angle = (wedge.theta2 + wedge.theta1) / 2.
                x = np.cos(np.deg2rad(angle))
                y = np.sin(np.deg2rad(angle))
                ax.text(x * 0.7, y * 0.7, f'{pct:.1f}%', ha='center', va='center', fontsize=10)

        ax.axis('equal')

        legend_labels = [
            f"{label} ({pct:.1f}%)" for label, pct in zip(labels, percentages)
        ]

        ax.legend(
            wedges,
            legend_labels,
            title="Operations",
            loc="center left",
            bbox_to_anchor=(1, 0.5)
        )

        plt.tight_layout()
        plt.savefig(FIGURES_PATH / f"{model}.png", bbox_inches="tight")
        plt.savefig(FIGURES_PATH / f"{model}.pdf", bbox_inches="tight")
        plt.savefig(FIGURES_PATH / f"{model}.eps", bbox_inches="tight")
        plt.close(fig)

        if "synthetic" in model.lower():
            synthetic_model = model
            synthetic_values = sizes
            synthetic_labels = labels
        else:
            llm_models.append(model)
            llm_values.append(sizes)
            llm_labels.append(labels)

    # Figura combinata in matrice 3x2
    total_plots = len(llm_models) + (1 if synthetic_model else 0)
    if total_plots > 0:
        fig, axs = plt.subplots(3, 2, figsize=(6, 9))
        axs = axs.flatten()

        legend_info = None

        for i, (model, values, labels) in enumerate(zip(llm_models, llm_values, llm_labels)):
            sizes = values
            total = sum(sizes)
            percentages = 100 * sizes / total if total > 0 else np.zeros_like(sizes)
            threshold = 2

            wedges, _ = axs[i].pie(
                sizes,
                labels=None,
                autopct=None,
                startangle=140,
                textprops=dict(color="black")
            )

            for wedge, pct in zip(wedges, percentages):
                if pct > threshold:
                    angle = (wedge.theta2 + wedge.theta1) / 2.
                    x = np.cos(np.deg2rad(angle))
                    y = np.sin(np.deg2rad(angle))
                    axs[i].text(x * 0.7, y * 0.7, f'{pct:.1f}%', ha='center', va='center', fontsize=10)

            axs[i].axis('equal')
            axs[i].set_title(PRETTY_MODEL_NAMES[model], fontsize=14, y=0.97)

            if legend_info is None:
                legend_labels = [f"{label}" for label in labels]
                legend_info = (wedges, legend_labels)

        # Posizione 5: synthetic
        if synthetic_model:
            i = 4  # posizione 5 nella griglia 0-indexed
            sizes = synthetic_values
            labels = synthetic_labels
            total = sum(sizes)
            percentages = 100 * sizes / total if total > 0 else np.zeros_like(sizes)
            threshold = 2

            wedges, _ = axs[i].pie(
                sizes,
                labels=None,
                autopct=None,
                startangle=140,
                textprops=dict(color="black")
            )

            for wedge, pct in zip(wedges, percentages):
                if pct > threshold:
                    angle = (wedge.theta2 + wedge.theta1) / 2.
                    x = np.cos(np.deg2rad(angle))
                    y = np.sin(np.deg2rad(angle))
                    axs[i].text(x * 0.7, y * 0.7, f'{pct:.1f}%', ha='center', va='center', fontsize=10)

            axs[i].axis('equal')
            axs[i].set_title(PRETTY_MODEL_NAMES[synthetic_model], fontsize=14, y=0.97)

        # Posizione 6: legenda
        if legend_info:
            axs[5].axis("off")
            fig.legend(
                *legend_info,
                title="Operations",
                loc="center",
                bbox_to_anchor=(0.75, 0.15)
            )

        plt.tight_layout()
        plt.savefig(FIGURES_PATH / "combined_models.png", bbox_inches="tight", pad_inches=0)
        plt.savefig(FIGURES_PATH / "combined_models.pdf", bbox_inches="tight", pad_inches=0)
        plt.savefig(FIGURES_PATH / "combined_models.eps", bbox_inches="tight", pad_inches=0)
        plt.close(fig)


if __name__ == "__main__":
    generate_pie_charts()
